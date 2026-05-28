package com.tencent.yolov11ncnn;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.tencent.yolov11ncnn.utils.OnboardingTTSHelper;

public class PixelWelcomeActivity extends AppCompatActivity {

    private static final long TRIPLE_TAP_WINDOW_MS = 600L;
    private static final long SKIP_DELAY_MS = 1500L;

    private ImageView appIcon;
    private Button btnStart;
    private View scanLine;
    private LinearLayout voiceIndicator;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OnboardingTTSHelper ttsHelper;
    private boolean isNavigating = false;
    private int tapCount = 0;
    private long firstTapTime = 0L;

    private AnimatorSet iconAnimSet;
    private AnimatorSet btnAnimSet;
    private ValueAnimator scanLineAnimator;

    private final Runnable navigateRunnable = this::navigateToOnboarding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pixel_welcome);

        appIcon = findViewById(R.id.appIcon);
        btnStart = findViewById(R.id.btnStart);
        scanLine = findViewById(R.id.scanLine);
        voiceIndicator = findViewById(R.id.voiceIndicator);

        initTTS();
        startScaleAnimation(appIcon, 0.90f, 1.05f, 1400, 0);
        startScaleAnimation(btnStart, 0.88f, 1.08f, 1200, 200);
        startScanLine();

        if (btnStart != null) {
            btnStart.setOnClickListener(v -> navigateToOnboarding());
        }

        View root = findViewById(R.id.rootLayout);
        if (root != null) {
            root.setOnTouchListener((v, event) -> {
                handleTouch(event);
                return true;
            });
        }
    }

    private void handleTouch(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || isNavigating) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (tapCount == 0 || now - firstTapTime > TRIPLE_TAP_WINDOW_MS) {
            tapCount = 1;
            firstTapTime = now;
        } else {
            tapCount++;
        }

        if (tapCount >= 3) {
            tapCount = 0;
            handler.removeCallbacks(navigateRunnable);
            skipToMain();
        } else {
            handler.removeCallbacks(navigateRunnable);
            handler.postDelayed(navigateRunnable, TRIPLE_TAP_WINDOW_MS);
        }
    }

    private void initTTS() {
        ttsHelper = new OnboardingTTSHelper(this, new OnboardingTTSHelper.TTSListener() {
            @Override
            public void onReady() {
                speak(getString(R.string.pixel_welcome_tts));
            }

            @Override
            public void onError() {
                // no-op
            }

            @Override
            public void onSpeakComplete() {
                if (!isFinishing() && voiceIndicator != null) {
                    voiceIndicator.setVisibility(View.GONE);
                }
            }
        });
    }

    private void speak(String text) {
        if (ttsHelper == null || isFinishing()) {
            return;
        }
        if (voiceIndicator != null) {
            voiceIndicator.setVisibility(View.VISIBLE);
        }
        ttsHelper.speak(text);
    }

    private void startScaleAnimation(View view, float min, float max, long duration, long delay) {
        if (view == null) {
            return;
        }

        ObjectAnimator sx = ObjectAnimator.ofFloat(view, "scaleX", min, max);
        sx.setRepeatCount(ValueAnimator.INFINITE);
        sx.setRepeatMode(ValueAnimator.REVERSE);

        ObjectAnimator sy = ObjectAnimator.ofFloat(view, "scaleY", min, max);
        sy.setRepeatCount(ValueAnimator.INFINITE);
        sy.setRepeatMode(ValueAnimator.REVERSE);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(sx, sy);
        set.setDuration(duration);
        set.setStartDelay(delay);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.start();

        if (view == appIcon) {
            iconAnimSet = set;
        } else if (view == btnStart) {
            btnAnimSet = set;
        }
    }

    private void startScanLine() {
        if (scanLine == null) {
            return;
        }

        scanLineAnimator = ValueAnimator.ofFloat(0f, 1f);
        scanLineAnimator.setDuration(3000);
        scanLineAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanLineAnimator.addUpdateListener(anim -> {
            int height = findViewById(android.R.id.content).getHeight();
            scanLine.setTranslationY(anim.getAnimatedFraction() * height);
        });
        scanLineAnimator.start();
    }

    private void navigateToOnboarding() {
        if (isNavigating) {
            return;
        }
        isNavigating = true;

        Animation fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                if (isFinishing()) {
                    return;
                }
                startActivity(new Intent(PixelWelcomeActivity.this, OnboardingActivity.class));
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });

        View content = findViewById(R.id.contentContainer);
        if (content != null) {
            content.startAnimation(fadeOut);
        } else {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
        }
    }

    private void skipToMain() {
        if (isNavigating) {
            return;
        }
        isNavigating = true;
        handler.removeCallbacks(navigateRunnable);
        SharedPrefsHelper.setFirstLaunchCompleted(this);
        SharedPrefsHelper.setTutorialCompleted(this, false);
        speak(getString(R.string.pixel_skip_tts));
        handler.postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }, SKIP_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (iconAnimSet != null) {
            iconAnimSet.cancel();
        }
        if (btnAnimSet != null) {
            btnAnimSet.cancel();
        }
        if (scanLineAnimator != null) {
            scanLineAnimator.cancel();
        }
        if (ttsHelper != null) {
            ttsHelper.shutdown();
            ttsHelper = null;
        }
        handler.removeCallbacksAndMessages(null);
    }
}
