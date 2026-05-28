package com.tencent.yolov11ncnn;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 语音指令管理器 - 监听用户语音，识别关键词触发场景查询
 * 支持单次监听和持续监听两种模式
 */
public class VoiceCommandManager {
    
    private static final String TAG = "VoiceCommandManager";
    
    // 触发关键词
    private static final String[] TRIGGER_KEYWORDS = {
            "前方", "前面", "障碍", "障碍物", "有什么", "什么情况",
            "周围", "环境", "帮我看", "看一下", "查询", "情况", "安全", "能走"
    };
    
    public interface VoiceCommandListener {
        void onSceneQueryRequested();
        void onVoiceError(String errorMessage);
        void onListeningStarted();
        void onListeningStopped();
    }
    
    private final Context context;
    private SpeechRecognizer speechRecognizer;
    private VoiceCommandListener listener;
    
    private boolean isListening = false;
    private boolean isContinuousMode = false;

    public VoiceCommandManager(Context context) {
        this.context = context;
        initSpeechRecognizer();
    }
    
    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "设备不支持语音识别");
            return;
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            
            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                if (listener != null) listener.onListeningStarted();
            }
            
            @Override
            public void onBeginningOfSpeech() {}
            
            @Override
            public void onRmsChanged(float rmsdB) {}
            
            @Override
            public void onBufferReceived(byte[] buffer) {}
            
            @Override
            public void onEndOfSpeech() {
                isListening = false;
                if (listener != null) listener.onListeningStopped();
            }
            
            @Override
            public void onError(int error) {
                isListening = false;
                String errorMessage = getErrorMessage(error);
                
                if (listener != null) {
                    listener.onListeningStopped();
                    // 只报严重错误
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && 
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        listener.onVoiceError(errorMessage);
                    }
                }
                
                if (isContinuousMode && error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    restartListeningWithDelay();
                }
            }
            
            @Override
            public void onResults(Bundle results) {
                isListening = false;
                if (listener != null) listener.onListeningStopped();
                
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                
                if (matches != null && !matches.isEmpty()) {
                    String recognizedText = matches.get(0);
                    Log.d(TAG, "识别结果: " + recognizedText);
                    
                    if (containsTriggerKeyword(recognizedText)) {
                        if (listener != null) listener.onSceneQueryRequested();
                    }
                }
                
                if (isContinuousMode) restartListeningWithDelay();
            }
            
            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                
                if (matches != null && !matches.isEmpty()) {
                    String partialText = matches.get(0);
                    
                    if (containsTriggerKeyword(partialText)) {
                        if (listener != null) listener.onSceneQueryRequested();
                        stopListening();
                    }
                }
            }
            
            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    public void setListener(VoiceCommandListener listener) {
        this.listener = listener;
    }
    
    public void startListening() {
        if (speechRecognizer == null || isListening) return;
        
        Intent intent = createRecognizerIntent();
        speechRecognizer.startListening(intent);
    }
    
    public void startContinuousListening() {
        isContinuousMode = true;
        startListening();
    }
    
    public void stopListening() {
        isContinuousMode = false;
        if (speechRecognizer != null) speechRecognizer.stopListening();
        isListening = false;
    }
    
    public boolean isListening() { return isListening; }
    public boolean isContinuousMode() { return isContinuousMode; }
    
    public void release() {
        stopListening();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }
    
    public static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    private boolean containsTriggerKeyword(String text) {
        if (text == null || text.isEmpty()) return false;
        
        String lowerText = text.toLowerCase();
        for (String keyword : TRIGGER_KEYWORDS) {
            if (lowerText.contains(keyword)) return true;
        }
        return false;
    }
    
    private void restartListeningWithDelay() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isContinuousMode && !isListening) startListening();
        }, 500);
    }
    
    private Intent createRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000);
        return intent;
    }
    
    private String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO: return "音频录制错误";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "权限不足";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "未识别到语音";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别器忙";
            case SpeechRecognizer.ERROR_SERVER: return "服务器错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "语音输入超时";
            default: return "未知错误: " + errorCode;
        }
    }
}
