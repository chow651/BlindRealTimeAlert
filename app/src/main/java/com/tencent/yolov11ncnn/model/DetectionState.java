package com.tencent.yolov11ncnn.model;

import com.tencent.yolov11ncnn.DetectionQueueManager;
import java.util.List;

/**
 * 检测状态数据模型
 * 封装检测结果和相关状态信息
 */
public class DetectionState {
    private final List<DetectionQueueManager.DetectionObject> objects;
    private final boolean isUserMoving;
    private final boolean isOrientationValid;
    private final long sessionTime;
    private final int objectCount;
    
    public DetectionState(
            List<DetectionQueueManager.DetectionObject> objects,
            boolean isUserMoving,
            boolean isOrientationValid,
            long sessionTime) {
        this.objects = objects;
        this.isUserMoving = isUserMoving;
        this.isOrientationValid = isOrientationValid;
        this.sessionTime = sessionTime;
        this.objectCount = objects != null ? objects.size() : 0;
    }
    
    // Getters
    public List<DetectionQueueManager.DetectionObject> getObjects() {
        return objects;
    }
    
    public boolean isUserMoving() {
        return isUserMoving;
    }
    
    public boolean isOrientationValid() {
        return isOrientationValid;
    }
    
    public long getSessionTime() {
        return sessionTime;
    }
    
    public int getObjectCount() {
        return objectCount;
    }
    
    /**
     * 获取状态描述文本
     */
    public String getStatusText() {
        long seconds = sessionTime / 1000;
        String motionStatus = isUserMoving ? "移动" : "静止";
        String orientationStatus = isOrientationValid ? "正常" : "偏离";
        return String.format("运行:%ds | %s | %s", seconds, motionStatus, orientationStatus);
    }
}
