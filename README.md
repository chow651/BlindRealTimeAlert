# BlindRealTimeAlert

BlindRealTimeAlert 是一个运行在 Android 端的实时环境感知与语音提示应用，面向视障出行辅助场景。项目使用 YOLOv11n 与 ncnn 在手机端完成目标检测，通过多级过滤、目标跟踪、风险判断和语音播报，把摄像头画面中的行人、车辆、障碍物等信息转换为简洁的中文提示。

本仓库提供完整 Android 工程，可直接编译、安装和二次开发。语音识别使用讯飞 SparkChain SDK，账号配置不随仓库提交；未配置账号时，视觉检测、风险播报、摄像头切换和 CPU/GPU 切换仍可运行。

## 功能特性

- 实时目标检测：基于 YOLOv11n ncnn 模型，在 Android 设备端执行推理。
- CPU/GPU 切换：主界面提供计算模式切换入口，支持在运行时切换 ncnn 推理后端。
- 相机预览与切换：支持前后摄像头切换，检测结果直接叠加在预览画面上。
- 风险播报：根据目标类型、方位、距离趋势和稳定性生成中文语音提示。
- 主动查询：按住语音按钮后说出查询指令，可主动获取前方环境描述。
- 运动状态判断：结合传感器数据区分行走、静止和手机转动，降低误播概率。

## 技术栈

- Android Java
- C++ / JNI
- ncnn
- OpenCV Mobile
- YOLOv11n ncnn model
- Android TextToSpeech
- 讯飞 SparkChain Android SDK
- Gradle / Android Gradle Plugin / CMake / NDK

## 目录结构

```text
BlindRealTimeAlert/
  app/
    build.gradle
    libs/
      Codec.aar
      SparkChain.aar
    src/main/
      AndroidManifest.xml
      assets/yolov11n_ncnn_model/
      java/com/tencent/yolov11ncnn/
      jni/
      res/
    src/test/java/com/tencent/yolov11ncnn/
  docs/
    architecture/
  gradle/
  build.gradle
  settings.gradle
```

关键源码位置：

- `app/src/main/java/com/tencent/yolov11ncnn/MainActivity.java`：主界面、相机入口、按钮交互。
- `app/src/main/java/com/tencent/yolov11ncnn/repository/DetectionRepository.java`：检测、播报、语音识别和状态控制主流程。
- `app/src/main/java/com/tencent/yolov11ncnn/DetectionQueueManager.java`：检测目标过滤、跟踪和稳定确认。
- `app/src/main/java/com/tencent/yolov11ncnn/VoiceCommandProcessor.java`：语音指令解析。
- `app/src/main/java/com/tencent/yolov11ncnn/XFYunOfflineSpeechManager.java`：讯飞 SparkChain 初始化和识别封装。
- `app/src/main/jni/yolov11.cpp`：ncnn 模型加载、推理和 NMS。
- `app/src/main/jni/yolov11ncnn.cpp`：JNI 接口和 Java 回调。
- `app/src/main/jni/ndkcamera.cpp`：Android 相机采集和预览绘制。

## 环境要求

推荐使用以下环境复现项目：

- Android Studio：稳定版
- JDK：17
- Android SDK：33
- Android Gradle Plugin：7.3.0
- Gradle Wrapper：仓库已提供
- NDK：26.1.10909125
- CMake：3.10.2
- Android 设备：Android 7.0 及以上

项目默认使用当前系统或 Android Studio 配置的 JDK。需要固定 JDK 路径时，可以在本机的 Gradle 用户配置或项目本地配置中加入：

```properties
org.gradle.java.home=/path/to/jdk17
```

## 复现步骤

### 1. 克隆仓库

```bash
git clone <repository-url>
cd BlindRealTimeAlert
```

### 2. 使用 Android Studio 打开项目

打开仓库根目录，等待 Gradle Sync 完成。同步失败时优先检查 JDK、Android SDK、NDK 和 CMake 版本。

### 3. 确认模型与 SDK 文件

仓库需要包含以下模型文件：

```text
app/src/main/assets/yolov11n_ncnn_model/yolov11n.ncnn.bin
app/src/main/assets/yolov11n_ncnn_model/yolov11n.ncnn.param
```

仓库需要包含以下本地 AAR 文件：

```text
app/libs/SparkChain.aar
app/libs/Codec.aar
```

如果你替换模型，需要保持 `yolov11.cpp` 中的输入尺寸、输出解析和类别定义与新模型一致。

### 4. 语音识别配置

仓库不包含讯飞开放平台账号信息。默认状态下，项目可以完成编译、安装和视觉检测测试；按住说话和语音指令会在缺少凭据时提示不可用。

需要启用讯飞语音识别时，请按讯飞 SparkChain Android SDK 文档在本机 debug 资源中添加凭据。相关本地资源路径已被 `.gitignore` 忽略，请勿提交任何个人账号信息。

### 5. 编译 Debug APK

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS 或 Linux：

```bash
./gradlew :app:assembleDebug
```

编译产物路径：

```text
app/build/outputs/apk/debug/yolov11ncnn-debug.apk
```

### 6. 安装到 Android 设备

连接手机并开启 USB 调试：

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/yolov11ncnn-debug.apk
```

如果电脑连接了多台设备：

```bash
adb -s <device-serial> install -r app/build/outputs/apk/debug/yolov11ncnn-debug.apk
```

首次启动时授予相机、麦克风和通知相关权限。不同 Android 版本的权限弹窗顺序会有差异。

## 使用说明

启动应用后，主界面会显示实时相机预览和检测结果。顶部状态区域显示系统运行状态，并提供 CPU/GPU 计算模式切换和摄像头切换入口。底部语音区域支持按住说话，松开后执行识别到的指令。

常用语音指令示例：

- `前面有什么`
- `查询`
- `切换摄像头`
- `切换 CPU`
- `切换 GPU`
- `暂停播报`
- `恢复播报`
- `帮助`

语音识别依赖本地讯飞账号配置。未配置时，可以通过界面按钮完成主要检测、切换和播报测试。

## 测试

运行单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

当前测试覆盖以下内容：

- 风险判断和场景分析
- 检测队列稳定确认
- 语音指令解析
- 主界面布局关键控件
- CPU/GPU 状态同步
- 讯飞账号配置不进入 main 资源

## 调试

常用 ADB 命令：

```bash
adb devices -l
adb logcat -c
adb logcat -v time
```

推荐关注以下日志标签：

- `ncnn`：模型加载、相机状态和 native 推理。
- `DetectionRepository`：检测、播报、语音识别和状态控制。
- `VoiceAnnouncement`：播报队列和 TTS 执行。
- `DetDiag`：检测链路诊断。
- `MotionDiag`：运动状态诊断。

Windows 日志过滤示例：

```powershell
adb logcat -v time | findstr "ncnn DetectionRepository VoiceAnnouncement DetDiag MotionDiag"
```

## 常见问题

### Gradle Sync 失败

检查 Android Studio 是否使用 JDK 17，并确认 Android SDK、NDK 和 CMake 已安装。需要固定 JDK 路径时，在本地配置 `org.gradle.java.home`。

### NDK 或 CMake 版本不匹配

在 Android Studio 的 SDK Manager 中安装：

- NDK 26.1.10909125
- CMake 3.10.2

然后重新同步项目。

### APK 启动后没有语音识别

检查本机 debug 资源是否已按讯飞 SparkChain Android SDK 要求提供凭据。未配置凭据时，语音识别不可用属于正常表现，视觉检测和界面按钮仍可使用。

### 模型加载失败

检查模型文件是否位于 `app/src/main/assets/yolov11n_ncnn_model/`，并确认文件名与代码中加载的名称一致。

### GPU 模式不可用

部分设备不支持 Vulkan 或相关驱动存在限制。遇到加载失败时，切换到 CPU 模式继续测试。

### 安装时报 `more than one device`

使用 `adb devices -l` 查看设备序列号，再执行：

```bash
adb -s <device-serial> install -r app/build/outputs/apk/debug/yolov11ncnn-debug.apk
```

## 架构说明

核心处理链路如下：

```text
Camera Frame
  -> JNI / ncnn YOLOv11 inference
  -> Java detection callback
  -> DetectionQueueManager filtering and tracking
  -> DetectionRepository state and risk decision
  -> VoiceAnnouncementManager TTS output
```

主动查询链路如下：

```text
Hold voice button
  -> SparkChain ASR
  -> VoiceCommandProcessor
  -> DetectionRepository command handling
  -> SceneAnalyzer response generation
  -> TTS output
```

更多模块说明见 `docs/architecture/README.md`。修改检测模型时，优先检查 native 输入输出解析。修改播报策略时，优先查看 `DetectionRepository`、`RiskManager`、`SceneAnalyzer` 和 `VoiceAnnouncementManager`。

## 许可证

仓库暂未声明开源许可证。发布到代码托管平台前，请根据实际授权需求补充 LICENSE 文件，并确认第三方 SDK、模型和 native 依赖的分发权限。
