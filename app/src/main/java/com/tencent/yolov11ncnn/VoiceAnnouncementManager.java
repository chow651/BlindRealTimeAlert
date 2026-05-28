/**
 * 语音播报管理器 - 负责TTS播报、冷却机制、优先级抢占
 */
package com.tencent.yolov11ncnn;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class VoiceAnnouncementManager {
    private static final String TAG = "VoiceAnnouncement";

    private static final float SPEECH_RATE = 1.1f;
    private static final float PITCH = 1.0f;
    private static final float HIGH_RISK_SPEECH_RATE = 1.3f;
    private static final float HIGH_RISK_PITCH = 1.1f;

    // 播报任务，按优先级排序
    private static class AnnouncementTask implements Comparable<AnnouncementTask> {
        final String text;
        final int label;
        final RiskManager.RiskLevel riskLevel;
        final long timestamp;
        final boolean isLooming;

        AnnouncementTask(String text, int label, RiskManager.RiskLevel riskLevel, boolean isLooming) {
            this.text = text;
            this.label = label;
            this.riskLevel = riskLevel;
            this.timestamp = System.currentTimeMillis();
            this.isLooming = isLooming;
        }

        @Override
        public int compareTo(AnnouncementTask other) {
            if (this.isLooming != other.isLooming) {
                return this.isLooming ? -1 : 1;
            }
            int priorityCompare = RiskManager.comparePriority(this.riskLevel, other.riskLevel);
            if (priorityCompare != 0) return priorityCompare;
            return Long.compare(this.timestamp, other.timestamp);
        }
    }

    private final Context context;
    private TextToSpeech tts;
    private final PriorityQueue<AnnouncementTask> announcementQueue;
    private final Map<String, Long> lastAnnounceTimeMap;
    private final Map<String, Long> lastVibrateTimeMap;
    private final AtomicBoolean isSpeaking;
    private final AtomicBoolean isInitialized;
    private final AtomicBoolean isPaused; // 暂停播报标志（语音识别期间）
    private AnnouncementTask currentTask = null;
    private boolean isUserMoving = true;

    private final AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    private long lastAnnouncementTime = 0;
    private static final long MIN_ANNOUNCEMENT_INTERVAL = 800L;

    // 内容去重
    private final Map<String, Long> recentAnnouncements = new HashMap<>();
    private static final long DUPLICATE_CONTENT_COOLDOWN = 8000L;
    
    // 方位冷却
    private final Map<String, Long> zoneCooldownStartTime = new HashMap<>();
    private final Map<String, String> zoneAnnouncedType = new HashMap<>();
    private final Map<String, Integer> zonePriorityMap = new HashMap<>();
    private static final long ZONE_COOLDOWN_MS = 5000L;
    private static final long ZONE_RESET_TIME = 15000L;
    
    // 态势播报冷却
    private long lastPathAnnouncementTime = 0;
    private static final long PATH_ANNOUNCEMENT_COOLDOWN = 8000L;
    private static final long PATH_DIRECTION_COOLDOWN = 12000L;
    private long lastDirectionSuggestionTime = 0;
    private int lastSuggestedDirection = 0;

    private final Map<String, Long> trackedObjects = new HashMap<>();
    private static final long OBJECT_DISAPPEAR_THRESHOLD = 2000L;

    public enum VibrationMode { DISCOVERY, PERSISTENT, DISAPPEARED }

    private static final String[] CLASS_NAMES_CN = {
            "人", "自行车", "汽车", "摩托车", "飞机", "公交车", "火车", "卡车", "船", "红绿灯",
            "消防栓", "停车标志", "停车计时器", "长椅", "鸟", "猫", "狗", "马", "羊", "牛",
            "大象", "熊", "斑马", "长颈鹿", "背包", "雨伞", "手提包", "领带", "手提箱", "飞盘",
            "滑雪板", "单板滑雪板", "运动球", "风筝", "棒球棒", "棒球手套", "滑板", "冲浪板",
            "网球拍", "瓶子", "酒杯", "杯子", "叉子", "刀子", "勺子", "碗", "香蕉", "苹果",
            "三明治", "橙子", "西兰花", "胡萝卜", "热狗", "披萨", "甜甜圈", "蛋糕", "椅子", "沙发",
            "盆栽", "床", "餐桌", "马桶", "电视", "笔记本电脑", "鼠标", "遥控器", "键盘", "手机",
            "微波炉", "烤箱", "烤面包机", "水槽", "冰箱", "书", "时钟", "花瓶", "剪刀", "泰迪熊",
            "吹风机", "牙刷"
    };

    public VoiceAnnouncementManager(Context context) {
        this.context = context;
        this.announcementQueue = new PriorityQueue<>();
        this.lastAnnounceTimeMap = new HashMap<>();
        this.lastVibrateTimeMap = new HashMap<>();
        this.isSpeaking = new AtomicBoolean(false);
        this.isInitialized = new AtomicBoolean(false);
        this.isPaused = new AtomicBoolean(false);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        initTTS();
        initAudioFocus();
    }

    private void initAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                                focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            if (tts != null) tts.stop();
                        }
                    })
                    .build();
        }
    }

    private void initTTS() {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.ENGLISH);
                }
                tts.setSpeechRate(SPEECH_RATE);
                tts.setPitch(PITCH);
                isInitialized.set(true);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        isSpeaking.set(true);
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        isSpeaking.set(false);
                        currentTask = null;
                        abandonAudioFocus();
                        processQueue();
                    }

                    @Override
                    public void onError(String utteranceId) {
                        isSpeaking.set(false);
                        currentTask = null;
                        abandonAudioFocus();
                        processQueue();
                    }
                });
                processQueue();
            }
        });
    }

    // 带方位的播报
    public void announceWithPosition(String className, int label,
            RiskManager.PositionZone positionZone,
            boolean isCloseRange, boolean isApproaching,
            boolean isMovingToCenter, boolean isGroundObstacle,
            float areaRatio, float centerX, float areaChangeRate) {
        if (className == null || className.isEmpty()) return;
        long currentTime = System.currentTimeMillis();
        
        boolean isVehicle = (label == 1 || label == 2 || label == 3 || label == 5 || label == 7);
        boolean isPerson = (label == 0);
        String objectType = isVehicle ? "vehicle" : (isPerson ? "person" : "obstacle");
        String zoneKey = positionZone.name();
        
        // 优先级：接近车辆>接近人>静止车辆>静止人>障碍物
        int currentPriority = 20;
        if (isApproaching) {
            currentPriority = isVehicle ? 100 : 80;
        } else {
            currentPriority = isVehicle ? 60 : (isPerson ? 40 : 20);
        }
        
        // 静止时只报紧急情况
        if (!isUserMoving) {
            boolean isFastApproachingVehicle = isVehicle && isApproaching && areaChangeRate > 0.50f;
            boolean isNearbyObstacle = !isPerson && !isVehicle && areaRatio > 0.03f;
            
            if (isFastApproachingVehicle) {
                Log.d(TAG, "静止时检测到快速接近车辆: " + className);
            } else if (isNearbyObstacle) {
                className = "障碍物，注意避让";
            } else {
                return;
            }
        }
        
        // 方位冷却检查
        Long cooldownStart = zoneCooldownStartTime.get(zoneKey);
        String announcedType = zoneAnnouncedType.get(zoneKey);
        Integer lastPriority = zonePriorityMap.get(zoneKey);
        
        if (cooldownStart != null) {
            long elapsed = currentTime - cooldownStart;
            
            if (elapsed > ZONE_RESET_TIME) {
                // 15秒后重置
                zoneCooldownStartTime.remove(zoneKey);
                zoneAnnouncedType.remove(zoneKey);
                zonePriorityMap.remove(zoneKey);
                cooldownStart = null;
                announcedType = null;
                lastPriority = null;
            } else if (elapsed < ZONE_COOLDOWN_MS) {
                // 冷却中，只有更高优先级才能播报
                if (lastPriority != null && currentPriority <= lastPriority) {
                    Log.d(TAG, "方位冷却中: " + zoneKey);
                    return;
                }
            } else {
                // 5-15秒之间，检查归并逻辑
                if (announcedType != null) {
                    if (announcedType.equals("person") && objectType.equals("obstacle")) {
                        return;
                    }
                    if (announcedType.equals("obstacle") && objectType.equals("person")) {
                        className = "避障，可能有行人";
                        objectType = "person_obstacle";
                    }
                }
                cooldownStart = null;
            }
        }
        
        // 构建播报
        RiskManager.RiskLevel riskLevel = RiskManager.determineRiskLevel(
                label, positionZone, isCloseRange, isApproaching, isMovingToCenter, isGroundObstacle);
        
        if (isVehicle && isApproaching) {
            riskLevel = RiskManager.RiskLevel.CRITICAL;
        } else if (isVehicle && areaRatio > 0.02f) {
            riskLevel = RiskManager.RiskLevel.HIGH;
        }

        // 队列积压检查
        synchronized (announcementQueue) {
            if (announcementQueue.size() >= 3 && !isApproaching && !isVehicle) {
                return;
            }
        }

        if (!isApproaching && !isVehicle && currentTime - lastAnnouncementTime < MIN_ANNOUNCEMENT_INTERVAL) {
            return;
        }

        String fullText = "注意" + positionZone.getDisplayName() + className;

        // 内容去重
        if (!isApproaching) {
            Long lastTime = recentAnnouncements.get(fullText);
            if (lastTime != null && currentTime - lastTime < DUPLICATE_CONTENT_COOLDOWN) {
                return;
            }
        }
        recentAnnouncements.put(fullText, currentTime);

        boolean isUrgent = isApproaching || isCloseRange;
        AnnouncementTask task = new AnnouncementTask(fullText, label, riskLevel, isUrgent);

        boolean shouldProcess = false;
        synchronized (announcementQueue) {
            if (isUrgent) {
                boolean alreadyExists = announcementQueue.stream()
                        .anyMatch(t -> t.isLooming && t.text.equals(fullText));
                if (alreadyExists) return;
            }

            if (shouldPreempt(task)) {
                if (tts != null && isSpeaking.get()) {
                    tts.stop();
                    isSpeaking.set(false);
                    currentTask = null;
                    abandonAudioFocus();
                }
                clearLowPriorityTasks(task);
                shouldProcess = true;
            }
            announcementQueue.offer(task);
        }
        
        // 更新冷却记录
        if (cooldownStart == null) {
            zoneCooldownStartTime.put(zoneKey, currentTime);
        }
        zoneAnnouncedType.put(zoneKey, objectType);
        if (lastPriority == null || currentPriority > lastPriority) {
            zonePriorityMap.put(zoneKey, currentPriority);
        }
        lastAnnouncementTime = currentTime;

        if (shouldProcess || (!isSpeaking.get() && isInitialized.get())) {
            processQueue();
        }
    }
    
    // 兼容旧接口
    public void announceWithPosition(String className, int label,
            RiskManager.PositionZone positionZone,
            boolean isCloseRange, boolean isApproaching,
            boolean isMovingToCenter, boolean isGroundObstacle,
            float areaRatio, float centerX) {
        announceWithPosition(className, label, positionZone, isCloseRange, isApproaching,
                isMovingToCenter, isGroundObstacle, areaRatio, centerX, 0f);
    }

    public void announceWithLabel(String text, int label, boolean isLooming) {
        if (text == null || text.isEmpty()) return;
        long currentTime = System.currentTimeMillis();
        RiskManager.RiskLevel riskLevel = RiskManager.getRiskLevel(label);
        String key = String.valueOf(label);

        synchronized (announcementQueue) {
            if (announcementQueue.size() >= 5 && riskLevel.ordinal() < RiskManager.RiskLevel.HIGH.ordinal())
                return;
        }
        if (!isLooming && (currentTime - lastAnnouncementTime < MIN_ANNOUNCEMENT_INTERVAL))
            return;

        if (isLooming || checkCooldown(key, riskLevel, currentTime)) {
            String prefix = RiskManager.getAnnouncementPrefix(riskLevel);
            String fullText = prefix + text;
            AnnouncementTask task = new AnnouncementTask(fullText, label, riskLevel, isLooming);

            boolean shouldProcess = false;
            synchronized (announcementQueue) {
                if (isLooming) {
                    boolean alreadyExists = announcementQueue.stream()
                            .anyMatch(t -> t.isLooming && t.text.equals(fullText));
                    if (alreadyExists) return;
                }

                if (shouldPreempt(task)) {
                    if (tts != null && isSpeaking.get()) {
                        tts.stop();
                        isSpeaking.set(false);
                        currentTask = null;
                        abandonAudioFocus();
                    }
                    clearLowPriorityTasks(task);
                    shouldProcess = true;
                }
                announcementQueue.offer(task);
            }
            lastAnnounceTimeMap.put(key, currentTime);
            lastAnnouncementTime = currentTime;
            trackedObjects.put(key, currentTime);

            if (shouldProcess || (!isSpeaking.get() && isInitialized.get())) {
                processQueue();
            }
        }
    }

    public void announce(String text) {
        announce(text, RiskManager.RiskLevel.CRITICAL);
    }

    public void announce(String text, RiskManager.RiskLevel riskLevel) {
        if (text == null || text.isEmpty()) return;
        AnnouncementTask task = new AnnouncementTask(text, -1, riskLevel, false);
        boolean shouldProcess = false;
        synchronized (announcementQueue) {
            if (shouldPreempt(task)) {
                if (tts != null && isSpeaking.get()) {
                    tts.stop();
                    isSpeaking.set(false);
                    currentTask = null;
                    abandonAudioFocus();
                }
                clearLowPriorityTasks(task);
                shouldProcess = true;
            }
            announcementQueue.offer(task);
        }
        if (shouldProcess || (!isSpeaking.get() && isInitialized.get())) {
            processQueue();
        }
    }

    public void stop() {
        if (tts != null) tts.stop();
        synchronized (announcementQueue) {
            announcementQueue.clear();
        }
        currentTask = null;
        isSpeaking.set(false);
    }

    /**
     * 暂停播报（语音识别期间）
     */
    public void pauseAnnouncements() {
        isPaused.set(true);
        // 停止当前播报
        if (tts != null && isSpeaking.get()) {
            tts.stop();
            isSpeaking.set(false);
            currentTask = null;
            abandonAudioFocus();
        }
        Log.d(TAG, "播报已暂停（语音识别中）");
    }

    /**
     * 恢复播报（语音识别结束）
     */
    public void resumeAnnouncements() {
        isPaused.set(false);
        Log.d(TAG, "播报已恢复");
        // 恢复后继续处理队列
        if (!isSpeaking.get() && isInitialized.get()) {
            processQueue();
        }
    }

    public void release() {
        stop();
        abandonAudioFocus();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        isInitialized.set(false);
    }

    public boolean isSpeaking() { return isSpeaking.get(); }

    public void resetCooldown(int label) {
        lastAnnounceTimeMap.remove(String.valueOf(label));
    }

    public void clearAllCooldowns() {
        lastAnnounceTimeMap.clear();
        lastVibrateTimeMap.clear();
        zoneCooldownStartTime.clear();
        zoneAnnouncedType.clear();
        zonePriorityMap.clear();
        recentAnnouncements.clear();
        lastPathAnnouncementTime = 0;
        lastDirectionSuggestionTime = 0;
        lastSuggestedDirection = 0;
    }

    private void triggerVibrationMode(VibrationMode mode, RiskManager.RiskLevel riskLevel) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                long[] pattern;
                if (mode == VibrationMode.DISCOVERY) pattern = new long[] { 0, 100 };
                else if (mode == VibrationMode.PERSISTENT) pattern = new long[] { 0, 60, 400, 60 };
                else pattern = new long[] { 0, 100, 50, 100 };

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
                else
                    v.vibrate(pattern, -1);
            }
        } catch (Exception e) {}
    }

    public void vibrate(RiskManager.RiskLevel riskLevel) {
        triggerVibrationMode(VibrationMode.DISCOVERY, riskLevel);
    }

    public void checkDisappearedObjects() {
        long currentTime = System.currentTimeMillis();
        List<String> disappearedKeys = new ArrayList<>();
        for (Map.Entry<String, Long> entry : trackedObjects.entrySet()) {
            if (currentTime - entry.getValue() > OBJECT_DISAPPEAR_THRESHOLD)
                disappearedKeys.add(entry.getKey());
        }
        for (String key : disappearedKeys) {
            trackedObjects.remove(key);
            triggerVibrationMode(VibrationMode.DISAPPEARED, RiskManager.RiskLevel.NORMAL);
        }
    }

    public void setUserMoving(boolean moving) {
        if (!this.isUserMoving && moving) {
            // 从静止变移动，重置方位记录
            zoneCooldownStartTime.clear();
            zoneAnnouncedType.clear();
            zonePriorityMap.clear();
        }
        this.isUserMoving = moving;
    }

    private boolean checkCooldown(String key, RiskManager.RiskLevel riskLevel, long currentTime) {
        Long lastTime = lastAnnounceTimeMap.get(key);
        return lastTime == null || (currentTime - lastTime >= RiskManager.getCooldownTime(riskLevel));
    }

    // 判断是否应该抢占当前播报
    private boolean shouldPreempt(AnnouncementTask newTask) {
        if (currentTask == null || !isSpeaking.get()) return false;
        if (newTask.isLooming && !currentTask.isLooming) return true;
        if (newTask.riskLevel == RiskManager.RiskLevel.CRITICAL
                && currentTask.riskLevel != RiskManager.RiskLevel.CRITICAL) return true;
        if (newTask.riskLevel == RiskManager.RiskLevel.HIGH 
                && currentTask.riskLevel == RiskManager.RiskLevel.NORMAL) return true;
        return false;
    }

    private void clearLowPriorityTasks(AnnouncementTask highPriorityTask) {
        synchronized (announcementQueue) {
            announcementQueue.removeIf(task -> {
                if (highPriorityTask.isLooming && !task.isLooming) return true;
                if (highPriorityTask.riskLevel == RiskManager.RiskLevel.CRITICAL
                        && task.riskLevel.ordinal() < RiskManager.RiskLevel.CRITICAL.ordinal()) return true;
                return false;
            });
        }
    }

    private void processQueue() {
        if (!isInitialized.get()) return;
        
        // 暂停期间不处理队列
        if (isPaused.get()) {
            Log.d(TAG, "播报暂停中，跳过队列处理");
            return;
        }

        // 清理过期缓存
        long now = System.currentTimeMillis();
        if (now % 10 == 0) {
            recentAnnouncements.entrySet().removeIf(entry -> now - entry.getValue() > DUPLICATE_CONTENT_COOLDOWN);
        }

        if (isSpeaking.get()) return;
        
        AnnouncementTask task;
        synchronized (announcementQueue) {
            while (true) {
                task = announcementQueue.poll();
                if (task == null) return;

                // 过时任务丢弃
                long age = System.currentTimeMillis() - task.timestamp;
                if (!task.isLooming && task.riskLevel.ordinal() < RiskManager.RiskLevel.HIGH.ordinal() && age > 3500) {
                    continue;
                }
                break;
            }
        }
        currentTask = task;
        speak(task);
    }

    private void speak(AnnouncementTask task) {
        if (tts == null || !isInitialized.get()) return;
        if (!requestAudioFocus()) return;
        
        if (task.riskLevel == RiskManager.RiskLevel.CRITICAL || task.isLooming) {
            tts.setSpeechRate(HIGH_RISK_SPEECH_RATE);
            tts.setPitch(HIGH_RISK_PITCH);
        } else {
            tts.setSpeechRate(SPEECH_RATE);
            tts.setPitch(PITCH);
        }
        
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        String utteranceId = "announcement_" + task.timestamp;
        int result = tts.speak(task.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        
        if (result == TextToSpeech.SUCCESS) {
            Log.i(TAG, "播报: " + task.text);
            triggerVibrationMode(VibrationMode.DISCOVERY, task.riskLevel);
        } else {
            isSpeaking.set(false);
            currentTask = null;
            abandonAudioFocus();
            processQueue();
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null)
            return audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null)
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        else
            audioManager.abandonAudioFocus(null);
    }

    public static String getClassNameCN(int label) {
        return (label >= 0 && label < CLASS_NAMES_CN.length) ? CLASS_NAMES_CN[label] : "物体";
    }

    // 态势播报
    public boolean announcePathStatus(SceneAnalyzer.PathResult result) {
        if (result == null || !SceneAnalyzer.shouldAnnouncePath(result)) return false;
        
        long currentTime = System.currentTimeMillis();
        
        if (result.hasApproachingTarget) {
            if (currentTime - lastPathAnnouncementTime < 2000L) return false;
            announce(result.announcement, RiskManager.RiskLevel.CRITICAL);
            lastPathAnnouncementTime = currentTime;
            return true;
        }
        
        if (result.suggestedDirection != 0) {
            if (result.suggestedDirection == lastSuggestedDirection) {
                if (currentTime - lastDirectionSuggestionTime < PATH_DIRECTION_COOLDOWN) return false;
            }
            if (currentTime - lastPathAnnouncementTime < PATH_ANNOUNCEMENT_COOLDOWN) return false;
            
            announce(result.announcement, RiskManager.RiskLevel.HIGH);
            lastPathAnnouncementTime = currentTime;
            lastDirectionSuggestionTime = currentTime;
            lastSuggestedDirection = result.suggestedDirection;
            return true;
        }
        
        if (currentTime - lastPathAnnouncementTime < PATH_ANNOUNCEMENT_COOLDOWN) return false;
        
        announce(result.announcement, RiskManager.RiskLevel.NORMAL);
        lastPathAnnouncementTime = currentTime;
        return true;
    }
    
    public void clearPathCooldown() {
        lastPathAnnouncementTime = 0;
        lastDirectionSuggestionTime = 0;
        lastSuggestedDirection = 0;
    }
}
