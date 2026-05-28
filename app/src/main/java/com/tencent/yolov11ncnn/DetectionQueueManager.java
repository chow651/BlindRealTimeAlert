/**
 * 检测结果防抖管理器 - 负责目标跟踪、运动分析、播报决策
 */
package com.tencent.yolov11ncnn;

import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class DetectionQueueManager {
    private static final String TAG = "DetectionQueueManager";

    private final java.util.Deque<Long> personAppearanceTimestamps = new java.util.LinkedList<>();

    // 配置参数
    private static final long TRACKER_EXPIRE_TIME_MS = 1000L;
    private static final float IOU_THRESHOLD = 0.20f;
    private static final long HEARTBEAT_VOICE_INTERVAL = 20000L;
    private static final long HEARTBEAT_VIBRATE_INTERVAL = 8000L;
    
    // 滤噪参数（20fps标准）
    private static final long MIN_STABLE_TIME_MS = 1200L;   // 至少1.2秒
    private static final int MIN_STABLE_FRAMES = 18;        // 至少18帧（约900ms）
    private static final int MAX_MISSING_FRAMES = 3;        // 允许最多3帧丢失
    private static final float MIN_THREAT_AREA_RATIO = 0.015f;

    // 白名单：只关注这些类型
    private static final Map<Integer, String> WHITELIST_MAP = new HashMap<>();
    static {
        WHITELIST_MAP.put(0, "person");
        WHITELIST_MAP.put(1, "bicycle");
        WHITELIST_MAP.put(2, "car");
        WHITELIST_MAP.put(3, "motorcycle");
        WHITELIST_MAP.put(5, "bus");
        WHITELIST_MAP.put(7, "truck");
        WHITELIST_MAP.put(10, "fire hydrant");
        WHITELIST_MAP.put(11, "stop sign");
        WHITELIST_MAP.put(13, "bench");
        WHITELIST_MAP.put(16, "dog");
    }

    /**
     * 诊断快照：用于定位“原始检测 -> 滤噪 -> 可播报”各阶段损耗与滞后。
     */
    public static class DebugStats {
        public final long timestampMs;
        public final int rawInputCount;
        public final int whitelistPassCount;
        public final int spatialPassCount;
        public final int matchedTrackerCount;
        public final int newTrackerCount;
        public final int activeTrackerCount;
        public final int confirmedTrackerCount;
        public final int stableReadyTrackerCount;
        public final int needsAnnouncementCount;
        public final int approachingCount;
        public final int stationaryCount;

        public static final DebugStats EMPTY = new DebugStats(
                0L, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        );

        public DebugStats(long timestampMs,
                          int rawInputCount,
                          int whitelistPassCount,
                          int spatialPassCount,
                          int matchedTrackerCount,
                          int newTrackerCount,
                          int activeTrackerCount,
                          int confirmedTrackerCount,
                          int stableReadyTrackerCount,
                          int needsAnnouncementCount,
                          int approachingCount,
                          int stationaryCount) {
            this.timestampMs = timestampMs;
            this.rawInputCount = rawInputCount;
            this.whitelistPassCount = whitelistPassCount;
            this.spatialPassCount = spatialPassCount;
            this.matchedTrackerCount = matchedTrackerCount;
            this.newTrackerCount = newTrackerCount;
            this.activeTrackerCount = activeTrackerCount;
            this.confirmedTrackerCount = confirmedTrackerCount;
            this.stableReadyTrackerCount = stableReadyTrackerCount;
            this.needsAnnouncementCount = needsAnnouncementCount;
            this.approachingCount = approachingCount;
            this.stationaryCount = stationaryCount;
        }
    }

    // 检测目标对象
    public static class DetectionObject {
        private final int label;
        private final float prob;
        private final float x;
        private final float y;
        private final float width;
        private final float height;
        private final String className;
        private final RiskManager.RiskLevel riskLevel;

        // 运动状态
        public boolean isApproaching = false;
        public boolean isStationary = false;
        public boolean isMovingToCenter = false;
        public boolean isGroundObstacle = false;
        public boolean needsAnnouncement = false;
        public boolean needsVibration = false;
        public boolean isConfirmed = false;
        public boolean isStable = false;
        public float areaChangeRate = 0f;

        public DetectionObject(int label, float prob, float x, float y,
                float width, float height, String className) {
            this.label = label;
            this.prob = prob;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.className = className;
            this.riskLevel = RiskManager.getRiskLevel(label);
        }

        public int getLabel() { return label; }
        public float getProb() { return prob; }
        public float getX() { return x; }
        public float getY() { return y; }
        public float getWidth() { return width; }
        public float getHeight() { return height; }
        public String getClassName() { return className; }
        public RiskManager.RiskLevel getRiskLevel() { return riskLevel; }

        public float getCenterXNormalized(float screenWidth) {
            return (x + width / 2.0f) / screenWidth;
        }

        public float getCenterYNormalized(float screenHeight) {
            return (y + height / 2.0f) / screenHeight;
        }
        
        public float getTop() {
            return y;
        }
        
        public float getBottom() {
            return y + height;
        }

        public float getHeightRatio(float screenHeight) {
            return height / screenHeight;
        }

        public float getAreaRatio(float screenWidth, float screenHeight) {
            return (width * height) / (screenWidth * screenHeight);
        }

        public float getArea() {
            return width * height;
        }

        public RiskManager.PositionZone getPositionZone(float screenWidth) {
            return RiskManager.getPositionZone(getCenterXNormalized(screenWidth));
        }

        public boolean isCloseRange(float screenHeight) {
            return RiskManager.isCloseRange(getHeightRatio(screenHeight));
        }
        
        public boolean isVehicle() {
            return label == 1 || label == 2 || label == 3 || label == 5 || label == 7;
        }
        
        public boolean isPerson() {
            return label == 0;
        }
    }

    // 目标追踪器，记录单个目标的历史信息
    private class ObjectTracker {
        final int label;
        long firstSeenTime;
        long lastSeenTime;
        boolean isConfirmed;
        boolean isAnnounced;
        int seenFrameCount;
        long lastAnnounceTime = 0;
        long lastVibrateTime = 0;

        DetectionObject latestObject;
        
        // 历史记录，用于运动分析
        private final java.util.LinkedList<Float> areaHistory = new java.util.LinkedList<>();
        private final java.util.LinkedList<Float> centerXHistory = new java.util.LinkedList<>();
        private final java.util.LinkedList<Float> centerYHistory = new java.util.LinkedList<>();
        private final java.util.LinkedList<Float> aspectRatioHistory = new java.util.LinkedList<>();
        private static final int MAX_HISTORY_SIZE = 20;

        float previousCenterX;
        float previousCenterY;
        float initialArea;
        
        // 运动状态
        boolean isApproaching;
        boolean isStationary;
        boolean isReceding;
        boolean isDepartingToEdge;
        boolean isMovingToCenter;
        float areaChangeRate;
        float positionChangeRate;
        float aspectRatioStability;
        
        // 稳定性计数
        int stableFrameCount;
        int approachingFrameCount;
        int consecutiveFrames;          // 连续帧计数
        int missingFrameCount;          // 丢失帧计数
        long lastUpdateTime;            // 上次更新时间
        
        // 阈值
        private static final int STABLE_FRAME_THRESHOLD = 12;
        private static final int APPROACHING_FRAME_THRESHOLD = 8;
        private static final float AREA_CHANGE_THRESHOLD_HIGH = 0.25f;
        private static final float AREA_CHANGE_THRESHOLD_LOW = 0.08f;
        private static final float POSITION_CHANGE_THRESHOLD = 0.015f;
        private static final float ASPECT_RATIO_STABLE_THRESHOLD = 0.05f;

        ObjectTracker(int label, long currentTime, DetectionObject obj) {
            this.label = label;
            this.firstSeenTime = currentTime;
            this.lastSeenTime = currentTime;
            this.lastUpdateTime = currentTime;
            this.isConfirmed = false;
            this.isAnnounced = false;
            this.seenFrameCount = 1;
            this.consecutiveFrames = 1;
            this.missingFrameCount = 0;
            this.latestObject = obj;
            
            float cx = obj.getCenterXNormalized(screenWidth);
            float cy = obj.getCenterYNormalized(screenHeight);
            float aspectRatio = obj.getWidth() / Math.max(obj.getHeight(), 1f);
            
            this.areaHistory.add(obj.getArea());
            this.centerXHistory.add(cx);
            this.centerYHistory.add(cy);
            this.aspectRatioHistory.add(aspectRatio);
            
            this.previousCenterX = cx;
            this.previousCenterY = cy;
            this.initialArea = obj.getArea();
            this.stableFrameCount = 0;
            this.approachingFrameCount = 0;
        }

        void update(long currentTime, DetectionObject obj) {
            seenFrameCount++;
            
            // 连续性检查：计算丢失的帧数（20fps约50ms一帧）
            long timeSinceLastUpdate = currentTime - lastUpdateTime;
            int expectedFrames = (int)(timeSinceLastUpdate / 50);  // 50ms = 1帧@20fps
            
            if (expectedFrames <= 1) {
                // 连续帧
                consecutiveFrames++;
                missingFrameCount = 0;
            } else if (expectedFrames <= MAX_MISSING_FRAMES + 1) {
                // 允许的丢帧范围内
                missingFrameCount += (expectedFrames - 1);
                consecutiveFrames++;
            } else {
                // 中断太久，重置连续帧计数
                Log.d(TAG, String.format("目标中断过久: label=%d, 丢失%d帧, 重置连续性", 
                        label, expectedFrames - 1));
                consecutiveFrames = 1;
                missingFrameCount = 0;
                firstSeenTime = currentTime;  // 重新开始计时
            }
            
            lastUpdateTime = currentTime;
            
            float currentCenterX = obj.getCenterXNormalized(screenWidth);
            float currentCenterY = obj.getCenterYNormalized(screenHeight);
            float currentAspectRatio = obj.getWidth() / Math.max(obj.getHeight(), 1f);
            
            // 更新历史
            if (areaHistory.size() >= MAX_HISTORY_SIZE) areaHistory.removeFirst();
            if (centerXHistory.size() >= MAX_HISTORY_SIZE) centerXHistory.removeFirst();
            if (centerYHistory.size() >= MAX_HISTORY_SIZE) centerYHistory.removeFirst();
            if (aspectRatioHistory.size() >= MAX_HISTORY_SIZE) aspectRatioHistory.removeFirst();
            
            areaHistory.add(obj.getArea());
            centerXHistory.add(currentCenterX);
            centerYHistory.add(currentCenterY);
            aspectRatioHistory.add(currentAspectRatio);
            
            analyzeMotionTrend(obj);
            
            lastSeenTime = currentTime;
            latestObject = obj;
            previousCenterX = currentCenterX;
            previousCenterY = currentCenterY;

            // 同步状态到DetectionObject
            latestObject.isApproaching = this.isApproaching;
            latestObject.isStationary = this.isStationary;
            latestObject.isMovingToCenter = this.isMovingToCenter;
            latestObject.areaChangeRate = this.areaChangeRate;

            // 确认逻辑：时间+帧数+连续性都满足才确认
            if (!isConfirmed) {
                long confirmTime = RiskManager.getConfirmTime(obj.getRiskLevel());
                boolean timeConfirmed = (currentTime - firstSeenTime) >= MIN_STABLE_TIME_MS;
                boolean frameConfirmed = seenFrameCount >= MIN_STABLE_FRAMES;
                boolean continuityConfirmed = consecutiveFrames >= MIN_STABLE_FRAMES;
                
                if (timeConfirmed && frameConfirmed && continuityConfirmed) {
                    isConfirmed = true;
                    Log.i(TAG, String.format("目标确认: label=%d, 累计%d帧, 连续%d帧, 耗时%dms",
                            label, seenFrameCount, consecutiveFrames, currentTime - firstSeenTime));
                    if (label == 0) {
                        synchronized (personAppearanceTimestamps) {
                            personAppearanceTimestamps.add(currentTime);
                        }
                    }
                }
            }
        }
        
        // 运动趋势分析：区分对向来车和用户走向静止物体
        private void analyzeMotionTrend(DetectionObject obj) {
            if (areaHistory.size() < 8) return;
            
            float currentCenterX = obj.getCenterXNormalized(screenWidth);
            float currentCenterY = obj.getCenterYNormalized(screenHeight);
            
            // 计算面积变化率
            int windowSize = Math.min(areaHistory.size(), 10);
            float oldAreaAvg = 0f, newAreaAvg = 0f;
            int halfWindow = windowSize / 2;
            int idx = 0;
            for (float a : areaHistory) {
                if (idx >= areaHistory.size() - windowSize) {
                    if (idx < areaHistory.size() - halfWindow) oldAreaAvg += a;
                    else newAreaAvg += a;
                }
                idx++;
            }
            oldAreaAvg /= halfWindow;
            newAreaAvg /= halfWindow;
            
            if (oldAreaAvg > 0) {
                areaChangeRate = (newAreaAvg - oldAreaAvg) / oldAreaAvg;
            }
            
            // 计算位置变化率
            float oldCenterX = centerXHistory.size() > windowSize ? 
                    getAverage(centerXHistory, areaHistory.size() - windowSize, halfWindow) : centerXHistory.getFirst();
            float oldCenterY = centerYHistory.size() > windowSize ?
                    getAverage(centerYHistory, areaHistory.size() - windowSize, halfWindow) : centerYHistory.getFirst();
            
            float dx = currentCenterX - oldCenterX;
            float dy = currentCenterY - oldCenterY;
            positionChangeRate = (float) Math.sqrt(dx * dx + dy * dy);
            
            // 计算宽高比稳定性
            float aspectRatioVariance = calculateVariance(aspectRatioHistory);
            float aspectRatioMean = calculateMean(aspectRatioHistory);
            aspectRatioStability = aspectRatioMean > 0 ? aspectRatioVariance / aspectRatioMean : 0;
            
            // 判断是否往边缘移动
            boolean movingToLeftEdge = currentCenterX < 0.25f && dx < -0.01f;
            boolean movingToRightEdge = currentCenterX > 0.75f && dx > 0.01f;
            isDepartingToEdge = movingToLeftEdge || movingToRightEdge;
            
            // 判断是否向中心移动
            float prevDistToCenter = (float) Math.sqrt(
                    Math.pow(previousCenterX - 0.5f, 2) + Math.pow(previousCenterY - 0.6f, 2));
            float currDistToCenter = (float) Math.sqrt(
                    Math.pow(currentCenterX - 0.5f, 2) + Math.pow(currentCenterY - 0.6f, 2));
            isMovingToCenter = (prevDistToCenter - currDistToCenter > 0.015f);
            
            // 综合判定
            boolean isInCenterZone = currentCenterX > 0.25f && currentCenterX < 0.75f;
            boolean areaGrowing = areaChangeRate > AREA_CHANGE_THRESHOLD_HIGH;
            boolean positionStable = positionChangeRate < POSITION_CHANGE_THRESHOLD;
            
            // 对向来车：面积快速变大 + 位置稳定 + 在中心区域
            if (areaGrowing && positionStable && (isInCenterZone || isMovingToCenter)) {
                approachingFrameCount++;
                if (approachingFrameCount >= APPROACHING_FRAME_THRESHOLD) {
                    // 宽高比太稳定可能是用户走向静止物体
                    if (aspectRatioStability < 0.02f && areaChangeRate < 0.5f) {
                        Log.d(TAG, "疑似用户走向静止物体: label=" + label);
                        isApproaching = false;
                        stableFrameCount++;
                    } else {
                        isApproaching = true;
                        isStationary = false;
                        isReceding = false;
                        stableFrameCount = 0;
                    }
                }
            } else {
                approachingFrameCount = Math.max(0, approachingFrameCount - 2);
            }
            
            // 远离判定
            if (areaChangeRate < -AREA_CHANGE_THRESHOLD_LOW && isDepartingToEdge) {
                isReceding = true;
                isApproaching = false;
                isStationary = false;
                stableFrameCount = 0;
                approachingFrameCount = 0;
            }
            
            // 静止判定
            if (Math.abs(areaChangeRate) < AREA_CHANGE_THRESHOLD_LOW && 
                aspectRatioStability < ASPECT_RATIO_STABLE_THRESHOLD) {
                stableFrameCount++;
                if (stableFrameCount >= STABLE_FRAME_THRESHOLD) {
                    isStationary = true;
                    isApproaching = false;
                    isReceding = false;
                }
            } else if (!isApproaching && !isReceding) {
                stableFrameCount = Math.max(0, stableFrameCount - 1);
            }
            
            Log.d(TAG, String.format("运动分析: label=%d, areaΔ=%.2f, posΔ=%.3f, approaching=%b, stationary=%b",
                    label, areaChangeRate, positionChangeRate, isApproaching, isStationary));
        }
        
        private float getAverage(java.util.LinkedList<Float> list, int startIdx, int count) {
            float sum = 0;
            int idx = 0;
            int counted = 0;
            for (float v : list) {
                if (idx >= startIdx && counted < count) {
                    sum += v;
                    counted++;
                }
                idx++;
            }
            return counted > 0 ? sum / counted : 0;
        }
        
        private float calculateMean(java.util.LinkedList<Float> list) {
            if (list.isEmpty()) return 0;
            float sum = 0;
            for (float v : list) sum += v;
            return sum / list.size();
        }
        
        private float calculateVariance(java.util.LinkedList<Float> list) {
            if (list.size() < 2) return 0;
            float mean = calculateMean(list);
            float variance = 0;
            for (float v : list) {
                variance += (v - mean) * (v - mean);
            }
            return variance / list.size();
        }

        public boolean isStable(long currentTime) {
            boolean timeStable = (currentTime - firstSeenTime) >= MIN_STABLE_TIME_MS;
            boolean frameStable = seenFrameCount >= MIN_STABLE_FRAMES;
            boolean continuityStable = consecutiveFrames >= MIN_STABLE_FRAMES;
            return timeStable && frameStable && continuityStable;
        }
        
        // 判断是否需要播报
        private boolean shouldAnnounce() {
            float areaRatio = latestObject.getAreaRatio(screenWidth, screenHeight);
            float centerX = latestObject.getCenterXNormalized(screenWidth);
            boolean isInCenterZone = centerX > 0.35f && centerX < 0.65f;
            
            if (areaRatio < MIN_THREAT_AREA_RATIO) return false;
            if (isReceding && isDepartingToEdge) return false;
            if (isStationary && !isInCenterZone && isAnnounced) return false;
            if (isApproaching) return true;
            if (isInCenterZone && areaRatio > 0.02f) return true;
            return !isAnnounced;
        }

        boolean isExpired(long currentTime) {
            return (currentTime - lastSeenTime) > TRACKER_EXPIRE_TIME_MS;
        }

        void processAnnouncements(long now) {
            latestObject.needsAnnouncement = false;
            latestObject.needsVibration = false;
            latestObject.isConfirmed = isConfirmed;
            latestObject.isStable = isStable(now);

            if (!isConfirmed || !isStable(now)) return;
            if (!shouldAnnounce()) return;

            // 首次播报
            if (!isAnnounced) {
                latestObject.needsAnnouncement = true;
                lastAnnounceTime = now;
                isAnnounced = true;
                return;
            }

            // 接近警报，间隔2秒
            if (isApproaching) {
                if (now - lastAnnounceTime > 2000L) {
                    latestObject.needsAnnouncement = true;
                    lastAnnounceTime = now;
                }
                return;
            }
            
            if (isStationary) return;
            
            // 心跳提醒：正前方大目标
            float centerX = latestObject.getCenterXNormalized(screenWidth);
            float areaRatio = latestObject.getAreaRatio(screenWidth, screenHeight);
            boolean isInCenterZone = centerX > 0.35f && centerX < 0.65f;
            boolean isLargeTarget = areaRatio > 0.08f;
            
            if (isInCenterZone && isLargeTarget && now - lastAnnounceTime > HEARTBEAT_VOICE_INTERVAL) {
                latestObject.needsAnnouncement = true;
                lastAnnounceTime = now;
            }
        }
    }

    private final Object trackersLock = new Object();
    private final List<ObjectTracker> activeTrackers = new ArrayList<>();
    private float screenWidth = 640.0f;
    private float screenHeight = 480.0f;
    private volatile DebugStats lastDebugStats = DebugStats.EMPTY;

    public DetectionQueueManager() {}

    public void setScreenSize(float width, float height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    // IOU计算
    private float calculateIOU(DetectionObject a, DetectionObject b) {
        float x1 = Math.max(a.x, b.x), y1 = Math.max(a.y, b.y);
        float x2 = Math.min(a.x + a.width, b.x + b.width), y2 = Math.min(a.y + a.height, b.y + b.height);
        if (x2 <= x1 || y2 <= y1) return 0f;
        float intersection = (x2 - x1) * (y2 - y1);
        return intersection / (a.width * a.height + b.width * b.height - intersection);
    }

    // 处理每帧检测结果
    public List<DetectionObject> addFrameDetection(List<DetectionObject> detections) {
        long currentTime = System.currentTimeMillis();
        List<DetectionObject> stableObjects = new ArrayList<>();
        List<ObjectTracker> matchedTrackers = new ArrayList<>();
        int rawInputCount = detections != null ? detections.size() : 0;
        int whitelistPassCount = 0;
        int spatialPassCount = 0;
        int matchedTrackerCount = 0;
        int newTrackerCount = 0;
        int confirmedTrackerCount = 0;
        int stableReadyTrackerCount = 0;
        int needsAnnouncementCount = 0;
        int approachingCount = 0;
        int stationaryCount = 0;

        synchronized (trackersLock) {
            if (detections != null) {
                for (DetectionObject obj : detections) {
                    if (!WHITELIST_MAP.containsKey(obj.getLabel())) continue;
                    whitelistPassCount++;
                    if (!RiskManager.isInValidSpatialRange(obj.getLabel(), obj.getCenterXNormalized(screenWidth),
                            obj.getAreaRatio(screenWidth, screenHeight))) continue;
                    spatialPassCount++;

                    // IOU匹配
                    ObjectTracker bestMatch = null;
                    float maxIOU = 0f;
                    for (ObjectTracker tracker : activeTrackers) {
                        if (tracker.label == obj.getLabel() && !matchedTrackers.contains(tracker)) {
                            float iou = calculateIOU(obj, tracker.latestObject);
                            if (iou > IOU_THRESHOLD && iou > maxIOU) {
                                maxIOU = iou;
                                bestMatch = tracker;
                            }
                        }
                    }

                    if (bestMatch != null) {
                        bestMatch.update(currentTime, obj);
                        matchedTrackers.add(bestMatch);
                        matchedTrackerCount++;
                    } else {
                        ObjectTracker nt = new ObjectTracker(obj.getLabel(), currentTime, obj);
                        activeTrackers.add(nt);
                        matchedTrackers.add(nt);
                        newTrackerCount++;
                    }
                }
            }

            // 清理过期tracker，处理播报
            Iterator<ObjectTracker> it = activeTrackers.iterator();
            while (it.hasNext()) {
                ObjectTracker t = it.next();
                if (t.isExpired(currentTime)) {
                    it.remove();
                    continue;
                }

                t.processAnnouncements(currentTime);
                if (t.isConfirmed) {
                    confirmedTrackerCount++;
                    if (t.isStable(currentTime)) {
                        stableReadyTrackerCount++;
                    }
                }
                if (t.latestObject.needsAnnouncement) {
                    needsAnnouncementCount++;
                }
                if (t.latestObject.isApproaching) {
                    approachingCount++;
                }
                if (t.latestObject.isStationary) {
                    stationaryCount++;
                }

                if (t.latestObject.needsAnnouncement) {
                    float yMaxNorm = (t.latestObject.getY() + t.latestObject.getHeight()) / screenHeight;
                    t.latestObject.isGroundObstacle = RiskManager.isGroundObstacle(t.label, yMaxNorm,
                            t.latestObject.getAreaRatio(screenWidth, screenHeight));
                }
                stableObjects.add(t.latestObject);
            }

            lastDebugStats = new DebugStats(
                    currentTime,
                    rawInputCount,
                    whitelistPassCount,
                    spatialPassCount,
                    matchedTrackerCount,
                    newTrackerCount,
                    activeTrackers.size(),
                    confirmedTrackerCount,
                    stableReadyTrackerCount,
                    needsAnnouncementCount,
                    approachingCount,
                    stationaryCount
            );
        }

        stableObjects.sort((o1, o2) -> RiskManager.comparePriority(o1.getRiskLevel(), o2.getRiskLevel()));
        return stableObjects;
    }

    public DebugStats getLastDebugStats() {
        return lastDebugStats;
    }

    public void reset() {
        synchronized (trackersLock) {
            activeTrackers.clear();
        }
    }

    public int getTrackedObjectCount() {
        synchronized (trackersLock) {
            return activeTrackers.size();
        }
    }

    public int getRecentPersonCount(long windowMs) {
        long now = System.currentTimeMillis();
        int count = 0;
        synchronized (personAppearanceTimestamps) {
            Iterator<Long> it = personAppearanceTimestamps.iterator();
            while (it.hasNext()) {
                long ts = it.next();
                if (now - ts > windowMs) {
                    it.remove();
                } else {
                    count++;
                }
            }
        }
        return count;
    }
    
    // 获取当前目标（场景查询用，条件放宽）
    public List<DetectionObject> getCurrentObjects() {
        List<DetectionObject> objects = new ArrayList<>();
        long currentTime = System.currentTimeMillis();

        synchronized (trackersLock) {
            for (ObjectTracker tracker : activeTrackers) {
                if (tracker.seenFrameCount >= 3 && !tracker.isExpired(currentTime)) {
                    objects.add(tracker.latestObject);
                }
            }
        }

        Log.d(TAG, "getCurrentObjects: 返回 " + objects.size() + " 个目标");
        return objects;
    }

    public List<DetectionObject> getAllTrackedObjects() {
        List<DetectionObject> objects = new ArrayList<>();
        synchronized (trackersLock) {
            for (ObjectTracker tracker : activeTrackers) {
                objects.add(tracker.latestObject);
            }
        }
        return objects;
    }
    
    public float getScreenWidth() { return screenWidth; }
    public float getScreenHeight() { return screenHeight; }
}
