package com.tencent.yolov11ncnn.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/**
 * 引导界面语音播报助手
 * 专门用于 Splash/Welcome/Onboarding 等 UI 界面的 TTS 播报
 * 与 VoiceAnnouncementManager 独立，避免相互干扰
 */
public class OnboardingTTSHelper {
    private static final String TAG = "OnboardingTTS";

    private TextToSpeech tts;
    private boolean isReady = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface TTSListener {
        void onReady();
        void onError();
        void onSpeakComplete();
    }

    private TTSListener listener;

    public OnboardingTTSHelper(Context context, TTSListener listener) {
        this.listener = listener;

        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.ENGLISH);
                }
                isReady = true;
                // 在回调内部注册，与 VoiceAnnouncementManager 保持一致
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        Log.d(TAG, "开始播报: " + utteranceId);
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        Log.d(TAG, "播报完成: " + utteranceId);
                        if (listener != null) {
                            mainHandler.post(() -> listener.onSpeakComplete());
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        Log.e(TAG, "播报错误: " + utteranceId);
                    }
                });
                Log.i(TAG, "TTS 就绪");
                if (listener != null) {
                    mainHandler.post(() -> listener.onReady());
                }
            } else {
                Log.e(TAG, "TTS 引擎初始化失败, status=" + status);
                if (listener != null) {
                    mainHandler.post(() -> listener.onError());
                }
            }
        });
    }

    /**
     * 播报文本
     */
    public void speak(String text) {
        if (!isReady || tts == null) {
            Log.w(TAG, "TTS 未就绪，无法播报: " + text);
            return;
        }

        Log.i(TAG, "播报: " + text);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "onboarding_" + System.currentTimeMillis());
    }

    /**
     * 停止播报
     */
    public void stop() {
        if (tts != null) {
            tts.stop();
        }
    }

    /**
     * 释放资源
     */
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        isReady = false;
        listener = null;
    }

    /**
     * 检查是否就绪
     */
    public boolean isReady() {
        return isReady;
    }
}
