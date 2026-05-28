# 架构文档

本目录记录 BlindRealTimeAlert 的架构说明，便于开发者理解代码组织、核心链路和模块职责。

## 当前结构

应用主体采用 Android Activity、Repository、Manager 和 JNI 模块组合：

```text
MainActivity
  -> MainViewModel
  -> DetectionRepository
  -> DetectionQueueManager
  -> VoiceAnnouncementManager
  -> XFYunOfflineSpeechManager
  -> JNI / ncnn
```

核心职责：

- `MainActivity`：主界面、相机预览、按钮交互和权限流程。
- `MainViewModel`：界面状态转接和主流程调用。
- `DetectionRepository`：检测、语音识别、播报和计算模式控制。
- `DetectionQueueManager`：目标过滤、跟踪和稳定确认。
- `VoiceAnnouncementManager`：TTS 播报队列和冷却控制。
- `XFYunOfflineSpeechManager`：讯飞 SparkChain 初始化、监听和识别回调。
- `jni/`：相机采集、ncnn 推理、结果回调和画面绘制。

## 代码入口

- `app/src/main/java/com/tencent/yolov11ncnn/MainActivity.java`
- `app/src/main/java/com/tencent/yolov11ncnn/viewmodel/MainViewModel.java`
- `app/src/main/java/com/tencent/yolov11ncnn/repository/DetectionRepository.java`
- `app/src/main/java/com/tencent/yolov11ncnn/DetectionQueueManager.java`
- `app/src/main/jni/yolov11.cpp`
- `app/src/main/jni/yolov11ncnn.cpp`
