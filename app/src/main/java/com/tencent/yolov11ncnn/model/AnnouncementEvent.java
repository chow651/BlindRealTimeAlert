package com.tencent.yolov11ncnn.model;

/**
 * 播报事件数据模型
 */
public class AnnouncementEvent {
    private final String text;
    private final long timestamp;
    
    public AnnouncementEvent(String text) {
        this.text = text;
        this.timestamp = System.currentTimeMillis();
    }
    
    public String getText() {
        return text;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
}
