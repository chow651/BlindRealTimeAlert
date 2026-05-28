package com.tencent.yolov11ncnn;

import java.util.ArrayDeque;

/**
 * 查询场景专用运动分类器：
 * 区分“正在走动”与“站立时轻微转动/手抖”，避免静止查询误入动态文案。
 */
public class QueryMotionClassifier {
    private static final float QUERY_ACCEL_THRESHOLD = 0.65f;
    private static final float QUERY_GYRO_THRESHOLD = 0.80f;
    private static final int QUERY_WINDOW_MS = 1200;
    private static final int QUERY_MOVING_TIMEOUT_MS = 900;
    private static final int QUERY_ACCEL_ONLY_MIN_HITS = 3;
    private static final int QUERY_ACCEL_COMBINED_MIN_HITS = 2;
    private static final int QUERY_GYRO_COMBINED_MIN_HITS = 2;

    private final ArrayDeque<Long> accelEventTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> gyroEventTimes = new ArrayDeque<>();
    private long lastWalkingLikeMotionTimeMs;

    public QueryMotionClassifier() {
        this(System.currentTimeMillis());
    }

    QueryMotionClassifier(long initialMotionTimeMs) {
        this.lastWalkingLikeMotionTimeMs = initialMotionTimeMs;
    }

    public void onAccelSample(float absAccelMagnitude, long nowMs) {
        if (absAccelMagnitude >= QUERY_ACCEL_THRESHOLD) {
            accelEventTimes.addLast(nowMs);
        }
        trimOldEvents(nowMs);
        refreshWalkingLikeTimestamp(nowMs);
    }

    public void onGyroSample(float gyroMagnitude, long nowMs) {
        if (gyroMagnitude >= QUERY_GYRO_THRESHOLD) {
            gyroEventTimes.addLast(nowMs);
        }
        trimOldEvents(nowMs);
        refreshWalkingLikeTimestamp(nowMs);
    }

    public boolean isMovingForQuery(long nowMs) {
        trimOldEvents(nowMs);
        if (isWalkingLikePattern()) {
            lastWalkingLikeMotionTimeMs = nowMs;
        }
        return nowMs - lastWalkingLikeMotionTimeMs <= QUERY_MOVING_TIMEOUT_MS;
    }

    public int getAccelHitCount(long nowMs) {
        trimOldEvents(nowMs);
        return accelEventTimes.size();
    }

    public int getGyroHitCount(long nowMs) {
        trimOldEvents(nowMs);
        return gyroEventTimes.size();
    }

    public long getLastWalkingLikeMotionAgeMs(long nowMs) {
        return nowMs - lastWalkingLikeMotionTimeMs;
    }

    private void refreshWalkingLikeTimestamp(long nowMs) {
        if (isWalkingLikePattern()) {
            lastWalkingLikeMotionTimeMs = nowMs;
        }
    }

    private boolean isWalkingLikePattern() {
        int accelHits = accelEventTimes.size();
        int gyroHits = gyroEventTimes.size();
        if (accelHits >= QUERY_ACCEL_ONLY_MIN_HITS) {
            return true;
        }
        return accelHits >= QUERY_ACCEL_COMBINED_MIN_HITS
                && gyroHits >= QUERY_GYRO_COMBINED_MIN_HITS;
    }

    private void trimOldEvents(long nowMs) {
        while (!accelEventTimes.isEmpty() && nowMs - accelEventTimes.peekFirst() > QUERY_WINDOW_MS) {
            accelEventTimes.pollFirst();
        }
        while (!gyroEventTimes.isEmpty() && nowMs - gyroEventTimes.peekFirst() > QUERY_WINDOW_MS) {
            gyroEventTimes.pollFirst();
        }
    }
}
