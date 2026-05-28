package com.tencent.yolov11ncnn;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.iflytek.sparkchain.core.SparkChain;
import com.iflytek.sparkchain.core.SparkChainConfig;
import com.iflytek.sparkchain.core.asr.ASR;
import com.iflytek.sparkchain.core.asr.AsrCallbacks;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 讯飞 SparkChain 离线语音识别管理器
 * 使用 ASR（语音听写）功能，完全离线，不需要 API Key
 */
public class XFYunOfflineSpeechManager {
    private static final String TAG = "XFYunOfflineSpeech";
    private static final int SAMPLE_RATE = 16000;
    private static final int BUFFER_SIZE = 320;
    private static final String XFYUN_APP_ID_RES = "xfyun_appid";
    private static final String XFYUN_API_SECRET_RES = "xfyun_api_secret";
    private static final String XFYUN_API_KEY_RES = "xfyun_api_key";
    private static boolean sdkInitialized = false;

    // 加载讯飞 SparkChain Native 库
    static {
        try {
            System.loadLibrary("spark");
            System.loadLibrary("SparkChain");
            Log.d(TAG, "讯飞 Native 库加载成功");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "讯飞 Native 库加载失败", e);
        }
    }

    private final Context context;
    private final Handler mainHandler;
    private ASR asr;
    private VoiceRecognitionCallback callback;
    private boolean isInitialized = false;
    private boolean isListening = false;

    private AudioRecord audioRecord;
    private Thread recordThread;
    private AtomicBoolean isRecording = new AtomicBoolean(false);
    private int bufferSizeInBytes;
    private int sessionCount = 0;

    public interface VoiceRecognitionCallback {
        void onResult(String text);
        void onPartialResult(String text);
        void onError(String error);
        void onReady();
    }

    public XFYunOfflineSpeechManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 初始化讯飞离线 ASR SDK
     */
    public void initialize(VoiceRecognitionCallback callback) {
        this.callback = callback;

        new Thread(() -> {
            try {
                Log.d(TAG, "开始初始化讯飞离线 ASR SDK...");

                // 全局初始化 SparkChain SDK（只需初始化一次）
                if (!sdkInitialized) {
                    String appId = getOptionalStringResource(XFYUN_APP_ID_RES);
                    String apiSecret = getOptionalStringResource(XFYUN_API_SECRET_RES);
                    String apiKey = getOptionalStringResource(XFYUN_API_KEY_RES);

                    if (isBlank(appId) || isBlank(apiSecret) || isBlank(apiKey)) {
                        throw new IllegalStateException("讯飞语音识别未配置");
                    }

                    Log.d(TAG, "讯飞配置已加载");

                    SparkChainConfig config = SparkChainConfig.builder()
                            .appID(appId)
                            .apiKey(apiKey)
                            .apiSecret(apiSecret)
                            .logLevel(3); // 日志级别

                    int ret = SparkChain.getInst().init(context, config);
                    if (ret == 0) {
                        sdkInitialized = true;
                        Log.d(TAG, "SparkChain SDK 全局初始化成功");
                    } else {
                        Log.e(TAG, "SparkChain SDK 全局初始化失败，错误码: " + ret);
                        throw new Exception("SDK 初始化失败，错误码: " + ret);
                    }
                }

                // 创建 ASR 实例
                asr = new ASR();
                asr.registerCallbacks(asrCallbacks);

                // 初始化音频录制
                bufferSizeInBytes = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );

                isInitialized = true;

                mainHandler.post(() -> {
                    Log.d(TAG, "讯飞离线 ASR SDK 初始化成功");
                    if (callback != null) {
                        callback.onReady();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "初始化讯飞离线 ASR SDK 失败", e);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError("初始化失败: " + e.getMessage());
                    }
                });
            }
        }).start();
    }

    public boolean hasConfiguration() {
        return !isBlank(getOptionalStringResource(XFYUN_APP_ID_RES))
                && !isBlank(getOptionalStringResource(XFYUN_API_SECRET_RES))
                && !isBlank(getOptionalStringResource(XFYUN_API_KEY_RES));
    }

    private String getOptionalStringResource(String name) {
        int resourceId = context.getResources().getIdentifier(name, "string", context.getPackageName());
        if (resourceId == 0) {
            return "";
        }
        return context.getString(resourceId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 开始语音识别
     */
    public void startListening() {
        if (!isInitialized) {
            Log.w(TAG, "讯飞离线 ASR SDK 未初始化，无法开始识别");
            if (callback != null) {
                callback.onError("语音识别未初始化");
            }
            return;
        }

        if (isListening) {
            Log.w(TAG, "已经在识别中");
            return;
        }

        new Thread(() -> {
            try {
                // 配置识别参数
                asr.language("zh_cn");        // 中文
                asr.domain("iat");            // 日常用语
                asr.accent("mandarin");       // 普通话
                asr.vinfo(true);              // 返回端点信息
                asr.dwa("wpgs");              // 动态修正

                // 启动识别
                sessionCount++;
                int ret = asr.start(String.valueOf(sessionCount));
                if (ret != 0) {
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onError("启动识别失败，错误码: " + ret);
                        }
                    });
                    return;
                }

                isListening = true;
                startAudioRecord();

                Log.d(TAG, "开始离线语音识别");

            } catch (Exception e) {
                Log.e(TAG, "启动识别失败", e);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError("启动识别失败: " + e.getMessage());
                    }
                });
            }
        }).start();
    }

    /**
     * 停止语音识别
     */
    public void stopListening() {
        if (!isListening) {
            return;
        }

        isListening = false;
        stopAudioRecord();

        new Thread(() -> {
            try {
                if (asr != null) {
                    asr.stop(false); // false = 不等待识别结果
                }
                Log.d(TAG, "停止离线语音识别");
            } catch (Exception e) {
                Log.e(TAG, "停止识别失败", e);
            }
        }).start();
    }

    /**
     * 释放资源
     */
    public void shutdown() {
        stopListening();
        isInitialized = false;

        if (asr != null) {
            asr = null;
        }

        Log.d(TAG, "释放资源");
    }

    /**
     * 检查是否正在识别
     */
    public boolean isListening() {
        return isListening;
    }

    /**
     * 启动音频录制
     */
    private void startAudioRecord() {
        if (isRecording.get()) {
            return;
        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeInBytes
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                return;
            }

            isRecording.set(true);
            recordThread = new Thread(recordRunnable);
            recordThread.start();

        } catch (Exception e) {
            Log.e(TAG, "启动音频录制失败", e);
        }
    }

    /**
     * 停止音频录制
     */
    private void stopAudioRecord() {
        isRecording.set(false);

        if (recordThread != null) {
            try {
                recordThread.interrupt();
                recordThread.join(500);
            } catch (InterruptedException e) {
                Log.e(TAG, "停止录制线程失败", e);
            }
            recordThread = null;
        }

        if (audioRecord != null) {
            try {
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "释放 AudioRecord 失败", e);
            }
            audioRecord = null;
        }
    }

    /**
     * 音频录制线程
     */
    private final Runnable recordRunnable = new Runnable() {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            audioRecord.startRecording();
            byte[] buffer = new byte[BUFFER_SIZE];

            while (isRecording.get() && isListening) {
                try {
                    int bytesRead = audioRecord.read(buffer, 0, BUFFER_SIZE);
                    if (bytesRead > 0 && asr != null) {
                        int ret = asr.write(buffer.clone());
                        if (ret != 0) {
                            Log.w(TAG, "写入音频数据失败: " + ret);
                        }
                    }
                    Thread.sleep(10);
                } catch (Exception e) {
                    if (isRecording.get()) {
                        Log.e(TAG, "录制音频出错", e);
                    }
                    break;
                }
            }
        }
    };

    /**
     * 讯飞离线 ASR 识别回调
     */
    private final AsrCallbacks asrCallbacks = new AsrCallbacks() {
        @Override
        public void onResult(ASR.ASRResult asrResult, Object usrTag) {
            int status = asrResult.getStatus();
            String result = asrResult.getBestMatchText();

            mainHandler.post(() -> {
                if (callback == null) return;

                if (status == 0) {
                    // 第一块结果（部分结果）
                    callback.onPartialResult(result);
                } else if (status == 1) {
                    // 中间结果（部分结果）
                    callback.onPartialResult(result);
                } else if (status == 2) {
                    // 最后一块结果（最终结果）
                    callback.onResult(result);
                }
            });
        }

        @Override
        public void onError(ASR.ASRError asrError, Object usrTag) {
            int code = asrError.getCode();
            String msg = asrError.getErrMsg();

            Log.e(TAG, "识别错误 - 错误码: " + code + ", 错误信息: " + msg);

            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onError("识别错误: " + msg + " (错误码: " + code + ")");
                }
            });

            // 发生错误时停止识别
            stopListening();
        }

        @Override
        public void onBeginOfSpeech() {
            Log.d(TAG, "开始说话");
        }

        @Override
        public void onEndOfSpeech() {
            Log.d(TAG, "结束说话");
        }
    };
}
