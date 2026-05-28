package com.tencent.yolov11ncnn.model;

/**
 * 语音状态数据模型
 */
public class VoiceState {
    private final boolean isListening;
    private final boolean isAvailable;
    private final String hintText;
    
    public VoiceState(boolean isListening, boolean isAvailable) {
        this.isListening = isListening;
        this.isAvailable = isAvailable;
        this.hintText = isListening ? "正在监听...松开结束" : "按住说话查询";
    }
    
    public boolean isListening() {
        return isListening;
    }
    
    public boolean isAvailable() {
        return isAvailable;
    }
    
    public String getHintText() {
        return hintText;
    }
}
