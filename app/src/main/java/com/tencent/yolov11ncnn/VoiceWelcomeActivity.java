package com.tencent.yolov11ncnn;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class VoiceWelcomeActivity extends AppCompatActivity {

    private static final String TAG = "VoiceWelcomeActivity";
    private static final int REQUEST_PERMISSIONS = 100;
    private static final long TRIPLE_TAP_WINDOW_MS = 600L;

    private enum Step {
        STARTUP,
        PERMISSION_REQUEST,
        ASK_TUTORIAL,
        TUTORIAL,
        COMPLETE
    }

    private static final class VoiceScript {
        static final String STARTUP = "实时提示助手已就绪。";
        static final String PERMISSION_REQUEST = "应用需要相机、麦克风与振动权限。请双击屏幕发起授权。";
        static final String PERMISSION_GRANTED = "权限已授予。";
        static final String PERMISSION_DENIED = "部分权限被拒绝，功能可能受限。";
        static final String ASK_TUTORIAL = "是否需要收听简短教程？双击收听，三击跳过。";
        static final String TUTORIAL = "教程开始。请保持手机竖直并让摄像头朝前。行走时会自动播报障碍。双击可查询场景。说出静音可短暂停播。";
        static final String SKIP_TUTORIAL = "已跳过教程。双击进入主界面。";
        static final String ENTER_MAIN = "正在打开主界面。";
    }

    private TextToSpeech tts;
    private GestureDetector gestureDetector;
    private Step currentStep = Step.STARTUP;

    private TextView tvStepIndicator;
    private TextView tvContent;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private int tapCount = 0;
    private long firstTapTime = 0L;
    private boolean pendingDoubleTap = false;

    private final Runnable confirmDoubleTapRunnable = () -> {
        if (!pendingDoubleTap) {
            return;
        }
        pendingDoubleTap = false;
        tapCount = 0;
        firstTapTime = 0L;
        vibrate(50);
        handleDoubleTap();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!SharedPrefsHelper.isFirstLaunch(this)) {
            startMainActivity();
            finish();
            return;
        }

        setContentView(R.layout.activity_voice_welcome);

        tvStepIndicator = findViewById(R.id.tvStepIndicator);
        tvContent = findViewById(R.id.tvContent);

        initTTS();
        initGestureDetector();

        View touchArea = findViewById(R.id.touchArea);
        if (touchArea != null) {
            touchArea.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    long now = SystemClock.uptimeMillis();
                    if (tapCount == 0 || now - firstTapTime > TRIPLE_TAP_WINDOW_MS) {
                        tapCount = 1;
                        firstTapTime = now;
                    } else {
                        tapCount++;
                    }

                    if (tapCount >= 3) {
                        tapCount = 0;
                        firstTapTime = 0L;
                        pendingDoubleTap = false;
                        handler.removeCallbacks(confirmDoubleTapRunnable);
                        vibrate(50);
                        handleTripleTap();
                        return true;
                    }
                }

                gestureDetector.onTouchEvent(event);
                return true;
            });
        }
    }

    private void initTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.CHINA);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                }
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "中文 TTS 语言不受支持");
                }
                tts.setSpeechRate(0.9f);
                tts.setPitch(1.0f);
                handler.postDelayed(this::startGuide, 500);
            } else {
                Log.e(TAG, "TTS 初始化失败");
            }
        });
    }

    private void initGestureDetector() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                boolean canTripleSkip = currentStep == Step.ASK_TUTORIAL || currentStep == Step.TUTORIAL;
                if (canTripleSkip) {
                    pendingDoubleTap = true;
                    handler.removeCallbacks(confirmDoubleTapRunnable);
                    handler.postDelayed(confirmDoubleTapRunnable, TRIPLE_TAP_WINDOW_MS);
                    return true;
                }

                vibrate(50);
                handleDoubleTap();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                vibrate(100);
                repeatCurrentStep();
            }
        });
    }

    private void startGuide() {
        currentStep = Step.STARTUP;
        speak(VoiceScript.STARTUP);
        updateUI("启动", VoiceScript.STARTUP);
        handler.postDelayed(this::checkPermissions, 1500);
    }

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.VIBRATE
        };

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            SharedPrefsHelper.setPermissionsGranted(this, true);
            askTutorial();
        } else {
            currentStep = Step.PERMISSION_REQUEST;
            speak(VoiceScript.PERMISSION_REQUEST);
            updateUI("权限", VoiceScript.PERMISSION_REQUEST);
        }
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.VIBRATE
        };
        ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        SharedPrefsHelper.setPermissionsGranted(this, allGranted);
        if (allGranted) {
            speak(VoiceScript.PERMISSION_GRANTED);
            handler.postDelayed(this::askTutorial, 1000);
        } else {
            speak(VoiceScript.PERMISSION_DENIED);
            handler.postDelayed(this::askTutorial, 1500);
        }
    }

    private void askTutorial() {
        currentStep = Step.ASK_TUTORIAL;
        speak(VoiceScript.ASK_TUTORIAL);
        updateUI("教程", VoiceScript.ASK_TUTORIAL);
    }

    private void playTutorial() {
        currentStep = Step.TUTORIAL;
        speak(VoiceScript.TUTORIAL);
        updateUI("教程", VoiceScript.TUTORIAL);
        SharedPrefsHelper.setTutorialCompleted(this, true);
    }

    private void skipTutorial() {
        currentStep = Step.COMPLETE;
        speak(VoiceScript.SKIP_TUTORIAL);
        updateUI("已跳过", VoiceScript.SKIP_TUTORIAL);
        SharedPrefsHelper.setTutorialCompleted(this, false);
    }

    private void completeGuide() {
        currentStep = Step.COMPLETE;
        SharedPrefsHelper.setFirstLaunchCompleted(this);
        speak(VoiceScript.ENTER_MAIN);
        handler.postDelayed(() -> {
            startMainActivity();
            finish();
        }, 1200);
    }

    private void handleDoubleTap() {
        switch (currentStep) {
            case STARTUP:
                break;
            case PERMISSION_REQUEST:
                requestPermissions();
                break;
            case ASK_TUTORIAL:
                playTutorial();
                break;
            case TUTORIAL:
            case COMPLETE:
                completeGuide();
                break;
        }
    }

    private void handleTripleTap() {
        pendingDoubleTap = false;
        handler.removeCallbacks(confirmDoubleTapRunnable);

        switch (currentStep) {
            case ASK_TUTORIAL:
            case TUTORIAL:
                if (tts != null) {
                    tts.stop();
                }
                skipTutorial();
                break;
            default:
                break;
        }
    }

    private void repeatCurrentStep() {
        switch (currentStep) {
            case STARTUP:
                speak(VoiceScript.STARTUP);
                break;
            case PERMISSION_REQUEST:
                speak(VoiceScript.PERMISSION_REQUEST);
                break;
            case ASK_TUTORIAL:
                speak(VoiceScript.ASK_TUTORIAL);
                break;
            case TUTORIAL:
                speak(VoiceScript.TUTORIAL);
                break;
            case COMPLETE:
                speak(VoiceScript.SKIP_TUTORIAL);
                break;
        }
    }

    private void speak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_welcome_utterance");
        }
    }

    private void updateUI(String step, String content) {
        if (tvStepIndicator != null) {
            tvStepIndicator.setText(step);
        }
        if (tvContent != null) {
            tvContent.setText(content);
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long milliseconds) {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(milliseconds);
        }
    }

    private void startMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(confirmDoubleTapRunnable);
        handler.removeCallbacksAndMessages(null);

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Disabled during onboarding flow.
    }
}
