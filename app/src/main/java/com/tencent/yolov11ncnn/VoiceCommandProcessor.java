package com.tencent.yolov11ncnn;

/**
 * 语音指令处理器
 * 解析用户语音指令并执行相应操作
 */
public class VoiceCommandProcessor {
    private static final String TAG = "VoiceCommandProcessor";

    public interface CommandCallback {
        void onQueryObstacles();
        void onSwitchCamera();
        void onSwitchCpu();
        void onSwitchGpu();
        void onTakeScreenshot();
        void onPauseAnnouncement();
        void onResumeAnnouncement();
        void onHelp();
        void onUnknownCommand(String text);
    }

    private final CommandCallback callback;

    public VoiceCommandProcessor(CommandCallback callback) {
        this.callback = callback;
    }

    /**
     * 处理识别到的文本
     */
    public void processCommand(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        String normalized = text.trim().toLowerCase();
        boolean wantsSwitch = containsAny(normalized, "切换", "切到", "换", "改成");
        boolean mentionsCamera = containsAny(normalized, "摄像头", "镜头", "前置", "后置");
        boolean mentionsCpu = containsAny(normalized, "cpu", "中央处理器");
        boolean mentionsGpu = containsAny(normalized, "gpu", "显卡", "图形处理器");

        // 查询障碍物
        if (containsAny(normalized, "查询", "障碍物", "有什么", "前面", "眼前")) {
            callback.onQueryObstacles();
        }
        // 切换 CPU
        else if (mentionsCpu && (wantsSwitch || containsAny(normalized, "模式"))) {
            callback.onSwitchCpu();
        }
        // 切换 GPU
        else if (mentionsGpu && (wantsSwitch || containsAny(normalized, "模式"))) {
            callback.onSwitchGpu();
        }
        // 切换摄像头
        else if (mentionsCamera && (wantsSwitch || containsAny(normalized, "前置", "后置"))) {
            callback.onSwitchCamera();
        }
        // 截图
        else if (containsAny(normalized, "截图", "拍照", "保存", "截屏")) {
            callback.onTakeScreenshot();
        }
        // 暂停播报
        else if (containsAny(normalized, "暂停", "停止", "安静", "别说")) {
            callback.onPauseAnnouncement();
        }
        // 恢复播报
        else if (containsAny(normalized, "恢复", "继续", "开始", "播报")) {
            callback.onResumeAnnouncement();
        }
        // 帮助
        else if (containsAny(normalized, "帮助", "怎么用", "使用", "教程")) {
            callback.onHelp();
        }
        // 未知指令
        else {
            callback.onUnknownCommand(text);
        }
    }

    /**
     * 检查文本是否包含任意关键词
     */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
