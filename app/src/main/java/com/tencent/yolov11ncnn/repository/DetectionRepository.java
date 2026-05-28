package com.tencent.yolov11ncnn.repository;

import android.content.Context;
import android.hardware.SensorManager;
import android.util.Log;

import com.tencent.yolov11ncnn.BuildConfig;
import com.tencent.yolov11ncnn.DetectionQueueManager;
import com.tencent.yolov11ncnn.OrientationSensorManager;
import com.tencent.yolov11ncnn.RiskManager;
import com.tencent.yolov11ncnn.SceneAnalyzer;
import com.tencent.yolov11ncnn.UserMotionDetector;
import com.tencent.yolov11ncnn.VoiceAnnouncementManager;
import com.tencent.yolov11ncnn.XFYunOfflineSpeechManager;
import com.tencent.yolov11ncnn.VoiceCommandProcessor;
import com.tencent.yolov11ncnn.Yolov11Ncnn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class DetectionRepository {
    private static final String TAG = "DetectionRepository";
    private static final long ORIENTATION_WARNING_INTERVAL = 4000L;
    private static final String DIAG_TAG = "DetDiag";
    private static final boolean ENABLE_DET_DIAG = BuildConfig.DEBUG;
    private static final long DET_DIAG_INTERVAL_MS = 250L;
    private static final float STATIC_QUERY_RAW_FALLBACK_MIN_AREA_RATIO = 0.02f;
    private static final float STATIC_QUERY_RAW_FALLBACK_MIN_PROB = 0.35f;
    private static final Set<Integer> STATIC_QUERY_RAW_FALLBACK_LABELS = new HashSet<>(
            Arrays.asList(1, 2, 3, 5, 7, 10, 11, 13, 16, 56, 57, 58, 59, 60, 62, 63));

    private final Context context;
    private long sessionStartTime;

    private final Yolov11Ncnn yolov11ncnn;
    private final DetectionQueueManager detectionQueueManager;
    private final VoiceAnnouncementManager voiceAnnouncementManager;
    private final OrientationSensorManager orientationSensorManager;
    private final UserMotionDetector userMotionDetector;
    private final XFYunOfflineSpeechManager xfyunOfflineSpeechManager;
    private final VoiceCommandProcessor voiceCommandProcessor;

    private float screenWidth = 1280.0f;
    private float screenHeight = 720.0f;
    private android.content.res.AssetManager lastAssetManager;
    private int currentModelId = 0;
    private int currentCpuGpu = 1;

    private long orientationInvalidStartTime = 0L;
    private long lastOrientationWarningTime = 0L;
    private boolean wasOrientationInvalid = false;

    private DetectionCallback detectionCallback;
    private OrientationCallback orientationCallback;
    private AnnouncementCallback announcementCallback;
    private VoiceStateCallback voiceStateCallback;
    private ComputeModeCallback computeModeCallback;
    private long diagFrameSeq = 0L;
    private long lastDetCallbackTime = 0L;
    private long lastDetDiagLogTime = 0L;
    private final Object rawDetectionsLock = new Object();
    private List<DetectionQueueManager.DetectionObject> latestRawDetections = new ArrayList<>();

    public DetectionRepository(Context context) {
        this.context = context.getApplicationContext();
        this.sessionStartTime = System.currentTimeMillis();

        yolov11ncnn = new Yolov11Ncnn();
        detectionQueueManager = new DetectionQueueManager();
        voiceAnnouncementManager = new VoiceAnnouncementManager(this.context);
        orientationSensorManager = new OrientationSensorManager(this.context);

        SensorManager sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        userMotionDetector = new UserMotionDetector(sensorManager);

        // 讯飞配置仅从本地 debug 资源读取，公开仓库默认不启用。
        xfyunOfflineSpeechManager = new XFYunOfflineSpeechManager(this.context);
        voiceCommandProcessor = new VoiceCommandProcessor(new VoiceCommandProcessor.CommandCallback() {
            @Override
            public void onQueryObstacles() {
                handleQueryObstacles();
            }

            @Override
            public void onSwitchCamera() {
                handleSwitchCamera();
            }

            @Override
            public void onSwitchCpu() {
                handleSwitchComputeMode(0);
            }

            @Override
            public void onSwitchGpu() {
                handleSwitchComputeMode(1);
            }

            @Override
            public void onTakeScreenshot() {
                handleTakeScreenshot();
            }

            @Override
            public void onPauseAnnouncement() {
                voiceAnnouncementManager.pauseAnnouncements();
                dispatchAnnouncement("已暂停播报");
            }

            @Override
            public void onResumeAnnouncement() {
                voiceAnnouncementManager.resumeAnnouncements();
                dispatchAnnouncement("已恢复播报");
            }

            @Override
            public void onHelp() {
                handleHelp();
            }

            @Override
            public void onUnknownCommand(String text) {
                Log.d(TAG, "未识别的指令: " + text);
                dispatchAnnouncement("未识别的指令");
            }
        });

        setupCallbacks();
    }

    private void setupCallbacks() {
        yolov11ncnn.setDetectionCallback(this::handleDetectionResult);

        orientationSensorManager.setListener(new OrientationSensorManager.OrientationListener() {
            @Override
            public void onOrientationChanged(float pitch, float roll) {
                if (orientationCallback != null) {
                    orientationCallback.onOrientationChanged(true);
                }
                if (wasOrientationInvalid) {
                    wasOrientationInvalid = false;
                    orientationInvalidStartTime = 0L;
                    lastOrientationWarningTime = 0L;
                    String msg = "手机朝向已恢复";
                    voiceAnnouncementManager.announce(msg, RiskManager.RiskLevel.HIGH);
                    dispatchAnnouncement(msg);
                }
            }

            @Override
            public void onOrientationInvalid(float pitch, float roll) {
                if (orientationCallback != null) {
                    orientationCallback.onOrientationChanged(false);
                }

                long now = System.currentTimeMillis();
                if (!wasOrientationInvalid) {
                    wasOrientationInvalid = true;
                    orientationInvalidStartTime = now;
                    lastOrientationWarningTime = now;
                    String msg = "请将摄像头正对前方";
                    voiceAnnouncementManager.announce(msg, RiskManager.RiskLevel.HIGH);
                    dispatchAnnouncement(msg);
                }
            }
        });

    }

    public boolean loadModel(android.content.res.AssetManager assetManager, int modelId, int cpuGpu) {
        lastAssetManager = assetManager;
        currentModelId = modelId;
        boolean ok = yolov11ncnn.loadModel(assetManager, modelId, cpuGpu);
        Log.d(TAG, "loadModel result=" + ok + ", modelId=" + modelId + ", cpuGpu=" + cpuGpu);
        if (!ok) {
            dispatchAnnouncement("模型加载失败，请重试");
            return false;
        }
        currentCpuGpu = cpuGpu;
        notifyComputeModeChanged();
        return true;
    }

    public void startDetection(int facing) {
        savedFacing = facing;
        yolov11ncnn.openCamera(facing);
        orientationSensorManager.start();
        userMotionDetector.start();
    }

    public void stopDetection() {
        yolov11ncnn.closeCamera();
        orientationSensorManager.stop();
        userMotionDetector.stop();
    }

    public void setOutputWindow(android.view.Surface surface) {
        yolov11ncnn.setOutputWindow(surface);
    }

    public void setScreenSize(float width, float height) {
        screenWidth = width;
        screenHeight = height;
        detectionQueueManager.setScreenSize(width, height);
    }

    public void performSceneQuery() {
        Log.i(TAG, "performSceneQuery");
        voiceAnnouncementManager.clearAllCooldowns();
        float rollDegrees = orientationSensorManager.getCurrentRollDegrees();

        List<DetectionQueueManager.DetectionObject> currentObjects = detectionQueueManager.getCurrentObjects();
        List<DetectionQueueManager.DetectionObject> confirmedStableObjects =
                filterConfirmedStableObjects(currentObjects);
        List<DetectionQueueManager.DetectionObject> staticQueryObjects =
                buildStaticQueryObjects(confirmedStableObjects);
        boolean movingRaw = isUserMoving();
        boolean movingForQuery = isUserMovingForQuery();
        boolean staticFallback = shouldUseStaticQueryFallback(confirmedStableObjects, staticQueryObjects);

        if (!movingForQuery || staticFallback) {
            String announcement = SceneAnalyzer.generateStaticQueryAnnouncement(
                    staticQueryObjects, screenWidth, screenHeight);
            Log.i(TAG, "performSceneQuery static: raw=" + currentObjects.size()
                    + ", confirmedStable=" + confirmedStableObjects.size()
                    + ", staticQueryObjects=" + staticQueryObjects.size()
                    + ", rawFallbackAdded=" + Math.max(0, staticQueryObjects.size() - confirmedStableObjects.size())
                    + ", movingRaw=" + movingRaw
                    + ", movingForQuery=" + movingForQuery
                    + ", staticFallback=" + staticFallback
                    + ", speech=" + announcement);
            voiceAnnouncementManager.announce(announcement, RiskManager.RiskLevel.HIGH);
            dispatchAnnouncement(announcement);
            return;
        }

        SceneAnalyzer.SceneReport report = SceneAnalyzer.analyzeScene(
                currentObjects, screenWidth, screenHeight, rollDegrees);
        String announcement = SceneAnalyzer.generateAnnouncement(report);
        voiceAnnouncementManager.announce(announcement, RiskManager.RiskLevel.HIGH);

        dispatchAnnouncement(announcement);
    }

    /**
     * 查询场景下的静止回退判定：
     * 传感器偶发抖动会把静止误判为移动，这里在“目标轨迹明显稳定且无接近趋势”时
     * 仍按静止查询文案处理，避免回退到“前方空旷，查询结束”的动态文案。
     */
    private boolean shouldUseStaticQueryFallback(
            List<DetectionQueueManager.DetectionObject> confirmedStableObjects,
            List<DetectionQueueManager.DetectionObject> staticQueryObjects) {
        if (staticQueryObjects == null || staticQueryObjects.isEmpty()) {
            return false;
        }
        if (confirmedStableObjects == null || confirmedStableObjects.isEmpty()) {
            return true;
        }
        for (DetectionQueueManager.DetectionObject obj : confirmedStableObjects) {
            if (obj == null) {
                continue;
            }
            if (obj.isApproaching || obj.isMovingToCenter) {
                return false;
            }
        }
        return true;
    }

    private List<DetectionQueueManager.DetectionObject> buildStaticQueryObjects(
            List<DetectionQueueManager.DetectionObject> confirmedStableObjects) {
        List<DetectionQueueManager.DetectionObject> merged = new ArrayList<>();
        if (confirmedStableObjects != null) {
            merged.addAll(confirmedStableObjects);
        }

        if (hasLargeStaticObstacle(merged)) {
            return merged;
        }

        List<DetectionQueueManager.DetectionObject> rawSnapshot = snapshotRawDetections();
        for (DetectionQueueManager.DetectionObject obj : rawSnapshot) {
            if (obj == null || obj.isPerson()) {
                continue;
            }
            if (!STATIC_QUERY_RAW_FALLBACK_LABELS.contains(obj.getLabel())) {
                continue;
            }
            if (obj.getProb() < STATIC_QUERY_RAW_FALLBACK_MIN_PROB) {
                continue;
            }
            if (obj.getAreaRatio(screenWidth, screenHeight) < STATIC_QUERY_RAW_FALLBACK_MIN_AREA_RATIO) {
                continue;
            }
            merged.add(obj);
        }
        return merged;
    }

    private boolean hasLargeStaticObstacle(List<DetectionQueueManager.DetectionObject> objects) {
        if (objects == null || objects.isEmpty()) {
            return false;
        }
        for (DetectionQueueManager.DetectionObject obj : objects) {
            if (obj == null || obj.isPerson()) {
                continue;
            }
            if (obj.getAreaRatio(screenWidth, screenHeight) >= STATIC_QUERY_RAW_FALLBACK_MIN_AREA_RATIO) {
                return true;
            }
        }
        return false;
    }

    private List<DetectionQueueManager.DetectionObject> snapshotRawDetections() {
        synchronized (rawDetectionsLock) {
            return new ArrayList<>(latestRawDetections);
        }
    }

    private List<DetectionQueueManager.DetectionObject> filterConfirmedStableObjects(
            List<DetectionQueueManager.DetectionObject> objects) {
        List<DetectionQueueManager.DetectionObject> filtered = new ArrayList<>();
        if (objects == null) {
            return filtered;
        }
        for (DetectionQueueManager.DetectionObject obj : objects) {
            if (obj != null && obj.isConfirmed && obj.isStable) {
                filtered.add(obj);
            }
        }
        return filtered;
    }

    public void startVoiceListening() {
        if (xfyunOfflineSpeechManager != null) {
            xfyunOfflineSpeechManager.startListening();
            if (voiceStateCallback != null) {
                voiceStateCallback.onVoiceStateChanged(true);
            }
        } else {
            Log.w(TAG, "XFYun speech manager is null");
            if (voiceStateCallback != null) {
                voiceStateCallback.onVoiceStateChanged(false);
            }
        }
    }

    public void stopVoiceListening() {
        if (xfyunOfflineSpeechManager != null) {
            xfyunOfflineSpeechManager.stopListening();
            if (voiceStateCallback != null) {
                voiceStateCallback.onVoiceStateChanged(false);
            }
        }
    }

    public void switchCamera(int facing) {
        savedFacing = facing;
        yolov11ncnn.closeCamera();
        yolov11ncnn.openCamera(facing);
    }

    public void reset() {
        detectionQueueManager.reset();
        voiceAnnouncementManager.clearAllCooldowns();
    }

    public boolean isUserMoving() {
        return userMotionDetector != null && userMotionDetector.isUserMoving();
    }

    public boolean isUserMovingForQuery() {
        if (userMotionDetector == null) {
            return isUserMoving();
        }
        return userMotionDetector.isUserMovingForQuery();
    }

    public long getSessionTime() {
        return System.currentTimeMillis() - sessionStartTime;
    }

    public boolean isVoiceAvailable() {
        return xfyunOfflineSpeechManager != null && xfyunOfflineSpeechManager.hasConfiguration();
    }

    public boolean isVoiceListening() {
        return xfyunOfflineSpeechManager != null && xfyunOfflineSpeechManager.isListening();
    }

    /**
     * 初始化讯飞语音识别
     */
    public void initializeOfflineSpeechRecognition(VoiceInitCallback callback) {
        Log.d(TAG, "initializeOfflineSpeechRecognition called, xfyunOfflineSpeechManager=" + (xfyunOfflineSpeechManager != null));
        if (isVoiceAvailable()) {
            Log.d(TAG, "Starting XFYun offline speech manager initialization");
            xfyunOfflineSpeechManager.initialize(new XFYunOfflineSpeechManager.VoiceRecognitionCallback() {
                @Override
                public void onResult(String text) {
                    Log.d(TAG, "讯飞识别结果: " + text);
                    voiceCommandProcessor.processCommand(text);
                    if (voiceStateCallback != null) {
                        voiceStateCallback.onRecognitionResult(text, true);
                    }
                }

                @Override
                public void onPartialResult(String text) {
                    Log.d(TAG, "讯飞部分结果: " + text);
                    if (voiceStateCallback != null) {
                        voiceStateCallback.onRecognitionResult(text, false);
                    }
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "讯飞错误: " + error);
                    if (voiceStateCallback != null) {
                        voiceStateCallback.onVoiceError(error);
                    }
                }

                @Override
                public void onReady() {
                    Log.d(TAG, "讯飞 SDK 初始化完成, callback=" + (callback != null) + ", voiceStateCallback=" + (voiceStateCallback != null));
                    if (callback != null) {
                        callback.onInitialized();
                    }
                    if (voiceStateCallback != null) {
                        voiceStateCallback.onVoiceAvailable(true);
                    }
                }
            });
        } else {
            Log.w(TAG, "XFYun offline speech is not configured");
            if (voiceStateCallback != null) {
                voiceStateCallback.onVoiceAvailable(false);
            }
            if (callback != null) {
                callback.onInitialized();
            }
        }
    }

    // 语音指令处理方法
    private void handleQueryObstacles() {
        performSceneQuery();
    }

    private void handleSwitchCamera() {
        // 切换摄像头
        int newFacing = (savedFacing == 0) ? 1 : 0;
        stopDetection();
        startDetection(newFacing);
        String msg = (newFacing == 0) ? "已切换到后置摄像头" : "已切换到前置摄像头";
        dispatchAnnouncement(msg);
    }

    private void handleSwitchComputeMode(int targetCpuGpu) {
        if (targetCpuGpu == currentCpuGpu) {
            dispatchAnnouncement(targetCpuGpu == 0 ? "当前已是CPU模式" : "当前已是GPU模式");
            return;
        }

        if (lastAssetManager == null) {
            Log.w(TAG, "switch compute mode failed: asset manager not ready");
            dispatchAnnouncement("切换失败：模型未初始化");
            return;
        }

        boolean ok = yolov11ncnn.loadModel(lastAssetManager, currentModelId, targetCpuGpu);
        Log.d(TAG, "switch compute mode result=" + ok + ", targetCpuGpu=" + targetCpuGpu);
        if (ok) {
            currentCpuGpu = targetCpuGpu;
            notifyComputeModeChanged();
            dispatchAnnouncement(targetCpuGpu == 0 ? "已切换到CPU模式" : "已切换到GPU模式");
        } else {
            dispatchAnnouncement("切换失败，请稍后重试");
        }
    }

    private void notifyComputeModeChanged() {
        if (computeModeCallback != null) {
            computeModeCallback.onComputeModeChanged(currentCpuGpu);
        }
    }

    private void handleTakeScreenshot() {
        // TODO: 实现截图功能
        Log.d(TAG, "截图功能待实现");
        dispatchAnnouncement("截图功能开发中");
    }

    private void handleHelp() {
        String helpText = "可用指令：查询障碍物、切换摄像头、切换CPU、切换GPU、截图、暂停播报、恢复播报";
        dispatchAnnouncement(helpText);
    }

    private volatile boolean released = false;
    private volatile boolean inferencePaused = false;
    private int savedFacing = 0;

    public void pauseInference() {
        Log.d(TAG, "Pausing inference - closing camera for voice input");
        inferencePaused = true;
        yolov11ncnn.closeCamera();
    }

    public void resumeInference() {
        Log.d(TAG, "Resuming inference - reopening camera after voice input");
        inferencePaused = false;
        // Reopen camera
        yolov11ncnn.openCamera(savedFacing);
    }

    public void release() {
        if (released) {
            Log.d(TAG, "DetectionRepository already released, skipping");
            return;
        }
        released = true;

        Log.d(TAG, "DetectionRepository.release() called");

        Log.d(TAG, "Closing camera...");
        yolov11ncnn.closeCamera();
        Log.d(TAG, "Camera closed");

        Log.d(TAG, "Releasing orientation sensor...");
        orientationSensorManager.release();
        Log.d(TAG, "Orientation sensor released");

        Log.d(TAG, "Releasing voice announcement...");
        voiceAnnouncementManager.release();
        Log.d(TAG, "Voice announcement released");

        Log.d(TAG, "Stopping user motion detector...");
        userMotionDetector.stop();
        Log.d(TAG, "User motion detector stopped");

        Log.d(TAG, "DetectionRepository.release() completed");
    }

    private void checkOrientationWarning() {
        if (!wasOrientationInvalid) {
            return;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastOrientationWarningTime;
        if (elapsed > ORIENTATION_WARNING_INTERVAL) {
            lastOrientationWarningTime = now;
            String msg = "请将摄像头正对前方";
            voiceAnnouncementManager.announce(msg, RiskManager.RiskLevel.HIGH);
            dispatchAnnouncement(msg);
        }
    }

    private void handleDetectionResult(int[] labels, float[] probs, float[] rects, int count) {
        long nowMs = System.currentTimeMillis();
        diagFrameSeq++;
        long deltaMs = lastDetCallbackTime > 0L ? nowMs - lastDetCallbackTime : -1L;
        lastDetCallbackTime = nowMs;
        float rollDegrees = orientationSensorManager.getCurrentRollDegrees();
        checkOrientationWarning();

        List<DetectionQueueManager.DetectionObject> detections = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            detections.add(new DetectionQueueManager.DetectionObject(
                    labels[i],
                    probs[i],
                    rects[i * 4],
                    rects[i * 4 + 1],
                    rects[i * 4 + 2],
                    rects[i * 4 + 3],
                    VoiceAnnouncementManager.getClassNameCN(labels[i])
            ));
        }
        synchronized (rawDetectionsLock) {
            latestRawDetections = new ArrayList<>(detections);
        }

        List<DetectionQueueManager.DetectionObject> stable = detectionQueueManager.addFrameDetection(detections);
        DetectionQueueManager.DebugStats debugStats = detectionQueueManager.getLastDebugStats();
        List<DetectionQueueManager.DetectionObject> stableConfirmed = new ArrayList<>();
        for (DetectionQueueManager.DetectionObject obj : stable) {
            if (obj.isConfirmed && obj.isStable) {
                stableConfirmed.add(obj);
            }
        }

        boolean moving = isUserMoving();
        voiceAnnouncementManager.setUserMoving(moving);

        // Fix #1: no object/path announcement when orientation is invalid.
        if (wasOrientationInvalid) {
            logDetectionDiagnostics(nowMs, deltaMs, count, stable, debugStats, moving,
                    "SKIP_ORIENTATION_INVALID", null, false, null, false, stableConfirmed.size());
            notifyDetectionResult(stable);
            return;
        }

        if (!moving) {
            boolean hasApproaching = false;
            for (DetectionQueueManager.DetectionObject obj : stableConfirmed) {
                if (obj.isApproaching && obj.needsAnnouncement) {
                    hasApproaching = true;
                    break;
                }
            }
            if (!hasApproaching) {
                logDetectionDiagnostics(nowMs, deltaMs, count, stable, debugStats, false,
                        "SKIP_STATIC_NO_APPROACHING", null, false, null, false, stableConfirmed.size());
                notifyDetectionResult(stable);
                return;
            }
        }

        SceneAnalyzer.PathResult pathResult = SceneAnalyzer.analyzePath(
                stableConfirmed, screenWidth, screenHeight, rollDegrees);

        // Fix #2: if path is announced, skip target announcement to avoid duplicate audio.
        boolean pathAnnounced = false;
        if (SceneAnalyzer.shouldAnnouncePath(pathResult)) {
            pathAnnounced = voiceAnnouncementManager.announcePathStatus(pathResult);
        }
        if (pathAnnounced) {
            logDetectionDiagnostics(nowMs, deltaMs, count, stable, debugStats, moving,
                    "ANNOUNCE_PATH", pathResult, true, null, true, stableConfirmed.size());
            notifyDetectionResult(stable);
            return;
        }

        DetectionQueueManager.DetectionObject topTarget = findTopTarget(stableConfirmed);
        if (topTarget != null) {
            announceTarget(topTarget, rollDegrees);
            logDetectionDiagnostics(nowMs, deltaMs, count, stable, debugStats, moving,
                    "ANNOUNCE_TARGET", pathResult, false, topTarget, true, stableConfirmed.size());
        } else {
            logDetectionDiagnostics(nowMs, deltaMs, count, stable, debugStats, moving,
                    "NO_ANNOUNCEMENT", pathResult, false, null, false, stableConfirmed.size());
        }

        notifyDetectionResult(stable);
    }

    private void logDetectionDiagnostics(long nowMs,
                                         long deltaMs,
                                         int rawCount,
                                         List<DetectionQueueManager.DetectionObject> stable,
                                         DetectionQueueManager.DebugStats debugStats,
                                         boolean moving,
                                         String decision,
                                         SceneAnalyzer.PathResult pathResult,
                                         boolean pathAnnounced,
                                         DetectionQueueManager.DetectionObject topTarget,
                                         boolean force,
                                         int confirmedInputCount) {
        if (!ENABLE_DET_DIAG) return;
        if (!force && nowMs - lastDetDiagLogTime < DET_DIAG_INTERVAL_MS) return;
        lastDetDiagLogTime = nowMs;

        int stableNeeds = 0;
        int stableApproaching = 0;
        int stablePersons = 0;
        int stableVehicles = 0;
        for (DetectionQueueManager.DetectionObject obj : stable) {
            if (obj.needsAnnouncement) stableNeeds++;
            if (obj.isApproaching) stableApproaching++;
            if (obj.isPerson()) stablePersons++;
            if (obj.isVehicle()) stableVehicles++;
        }

        String pathStatus = pathResult != null ? pathResult.status.name() : "-";
        String pathText = (pathResult != null && pathResult.announcement != null && !pathResult.announcement.isEmpty())
                ? pathResult.announcement : "-";
        if (pathText.length() > 20) {
            pathText = pathText.substring(0, 20) + "...";
        }

        String topTargetSummary;
        if (topTarget == null) {
            topTargetSummary = "-";
        } else {
            topTargetSummary = "l=" + topTarget.getLabel()
                    + ",ar=" + String.format(Locale.US, "%.3f", topTarget.getAreaRatio(screenWidth, screenHeight))
                    + ",ap=" + (topTarget.isApproaching ? 1 : 0);
        }

        float fps = deltaMs > 0 ? (1000f / deltaMs) : 0f;
        DetectionQueueManager.DebugStats stats = debugStats != null ? debugStats : DetectionQueueManager.DebugStats.EMPTY;
        Log.i(DIAG_TAG,
                "f=" + diagFrameSeq
                        + " dt=" + deltaMs + "ms"
                        + " fps=" + String.format(Locale.US, "%.1f", fps)
                        + " moving=" + (moving ? 1 : 0)
                        + " orientInvalid=" + (wasOrientationInvalid ? 1 : 0)
                        + " raw=" + rawCount
                        + " wl=" + stats.whitelistPassCount
                        + " spatial=" + stats.spatialPassCount
                        + " matched=" + stats.matchedTrackerCount
                        + " new=" + stats.newTrackerCount
                        + " trackers=" + stats.activeTrackerCount
                        + " confirmed=" + stats.confirmedTrackerCount
                        + " stableReady=" + stats.stableReadyTrackerCount
                        + " need=" + stats.needsAnnouncementCount
                        + " appr=" + stats.approachingCount
                        + " stat=" + stats.stationaryCount
                        + " stableOut=" + stable.size()
                        + " confirmedInput=" + confirmedInputCount
                        + " stableNeed=" + stableNeeds
                        + " stableAppr=" + stableApproaching
                        + " stableP=" + stablePersons
                        + " stableV=" + stableVehicles
                        + " path=" + pathStatus
                        + " pathSay=" + (pathAnnounced ? 1 : 0)
                        + " pathText=" + pathText
                        + " top=" + topTargetSummary
                        + " decision=" + decision
        );
    }

    private DetectionQueueManager.DetectionObject findTopTarget(List<DetectionQueueManager.DetectionObject> objects) {
        DetectionQueueManager.DetectionObject topTarget = null;

        for (DetectionQueueManager.DetectionObject obj : objects) {
            if (!obj.needsAnnouncement) {
                continue;
            }

            if (topTarget == null) {
                topTarget = obj;
            } else {
                boolean newBetter = false;

                if (obj.isApproaching && !topTarget.isApproaching) {
                    newBetter = true;
                } else if (obj.isApproaching == topTarget.isApproaching) {
                    if (obj.isVehicle() && !topTarget.isVehicle()) {
                        newBetter = true;
                    } else if (obj.isVehicle() == topTarget.isVehicle()) {
                        newBetter = obj.getAreaRatio(screenWidth, screenHeight)
                                > topTarget.getAreaRatio(screenWidth, screenHeight);
                    }
                }

                if (newBetter) {
                    topTarget = obj;
                }
            }
        }

        return topTarget;
    }

    private void announceTarget(DetectionQueueManager.DetectionObject obj, float rollDegrees) {
        float compensatedCenterX = SceneAnalyzer.compensateCenterX(
                obj.getCenterXNormalized(screenWidth),
                obj.getCenterYNormalized(screenHeight),
                rollDegrees);
        RiskManager.PositionZone zone = RiskManager.getPositionZone(compensatedCenterX);
        String announcement = buildSimpleAnnouncement(obj, zone);

        voiceAnnouncementManager.announceWithPosition(
                announcement,
                obj.getLabel(),
                zone,
                obj.isCloseRange(screenHeight),
                obj.isApproaching,
                obj.isMovingToCenter,
                obj.isGroundObstacle,
                obj.getAreaRatio(screenWidth, screenHeight),
                compensatedCenterX,
                obj.areaChangeRate
        );
    }

    private String buildSimpleAnnouncement(DetectionQueueManager.DetectionObject obj, RiskManager.PositionZone zone) {
        if (obj.isVehicle()) {
            if (obj.isApproaching) {
                return "有" + obj.getClassName() + "正在靠近";
            } else if (obj.isStationary) {
                return "前方有障碍物";
            } else {
                return obj.getClassName();
            }
        } else if (obj.isPerson()) {
            return "前方有人";
        }
        return "前方有障碍物";
    }

    private void notifyDetectionResult(List<DetectionQueueManager.DetectionObject> objects) {
        if (detectionCallback != null) {
            detectionCallback.onDetectionResult(objects);
        }
    }

    private void dispatchAnnouncement(String text) {
        if (announcementCallback != null) {
            announcementCallback.onAnnouncement(text);
        }
    }

    public void setDetectionCallback(DetectionCallback callback) {
        detectionCallback = callback;
    }

    public void setOrientationCallback(OrientationCallback callback) {
        orientationCallback = callback;
    }

    public void setAnnouncementCallback(AnnouncementCallback callback) {
        announcementCallback = callback;
    }

    public void setVoiceStateCallback(VoiceStateCallback callback) {
        voiceStateCallback = callback;
    }

    public void setComputeModeCallback(ComputeModeCallback callback) {
        computeModeCallback = callback;
        notifyComputeModeChanged();
    }

    public interface DetectionCallback {
        void onDetectionResult(List<DetectionQueueManager.DetectionObject> objects);
    }

    public interface OrientationCallback {
        void onOrientationChanged(boolean isValid);
    }

    public interface AnnouncementCallback {
        void onAnnouncement(String text);
    }

    public interface ComputeModeCallback {
        void onComputeModeChanged(int cpuGpu);
    }

    public interface VoiceStateCallback {
        void onVoiceStateChanged(boolean isListening);
        void onVoiceAvailable(boolean available);
        void onVoiceError(String error);
        void onRecognitionResult(String text, boolean isFinal);
    }

    public interface VoiceInitCallback {
        void onInitialized();
    }
}
