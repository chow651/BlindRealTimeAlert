package com.tencent.yolov11ncnn;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.tencent.yolov11ncnn.utils.OnboardingTTSHelper;

public class OnboardingActivity extends AppCompatActivity {

    private static final int TOTAL_STEPS = 4;
    private static final long STEP_ACTION_DEBOUNCE_MS = 300L;

    private static final String[] TTS_TEXTS = {
            "第一步，请保持手机竖直，摄像头朝向前方。",
            "第二步，行走过程中，应用会自动识别并播报道路障碍。",
            "第三步，需要了解前方环境时，双击屏幕即可主动查询。",
            "第四步，说出静音可暂时暂停播报，再次双击可恢复播报。"
    };

    private static final int[] ICONS = {
            R.drawable.ic_pixel_camera,
            R.drawable.ic_pixel_volume,
            R.drawable.ic_pixel_mic,
            R.drawable.ic_pixel_volume
    };

    private static final String[] TITLES = {
            "手机姿态",
            "自动提醒",
            "场景查询",
            "暂停播报"
    };

    private static final String[] DESCS = {
            "请保持手机竖直，并让摄像头朝向前方。",
            "移动过程中会自动检测并播报障碍信息。",
            "双击屏幕任意位置，可随时询问前方场景。",
            "说“静音”可短暂停播，双击可继续播报。"
    };

    private int currentStep = 0;
    private long lastStepActionTime = 0L;
    private boolean isCompleting = false;
    private boolean suppressTapAdvanceOnce = false;

    private OnboardingTTSHelper ttsHelper;
    private GestureDetector gestureDetector;

    private ImageView icon;
    private TextView title;
    private TextView desc;
    private Button btnNext;
    private View dot0;
    private View dot1;
    private View dot2;
    private View dot3;

    private AnimatorSet iconAnimSet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        icon = findViewById(R.id.onboardingIcon);
        title = findViewById(R.id.onboardingTitle);
        desc = findViewById(R.id.onboardingDesc);
        btnNext = findViewById(R.id.btnOnboardingNext);
        dot0 = findViewById(R.id.dot0);
        dot1 = findViewById(R.id.dot1);
        dot2 = findViewById(R.id.dot2);
        dot3 = findViewById(R.id.dot3);

        ttsHelper = new OnboardingTTSHelper(this, new OnboardingTTSHelper.TTSListener() {
            @Override
            public void onReady() {
                ttsHelper.speak(TTS_TEXTS[0]);
            }

            @Override
            public void onError() {
                // no-op
            }

            @Override
            public void onSpeakComplete() {
                // no-op
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                suppressTapAdvanceOnce = true;
                if (ttsHelper != null) {
                    ttsHelper.speak(TTS_TEXTS[currentStep]);
                }
            }
        });

        if (btnNext != null) {
            btnNext.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                goNext();
            });
        }

        updateStep(0);
        startIconAnimation();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (gestureDetector != null && event != null) {
            gestureDetector.onTouchEvent(event);
        }

        // 任意位置单击都进入下一步，按钮区域除外（按钮自身已有点击逻辑）。
        if (event != null
                && event.getAction() == MotionEvent.ACTION_UP
                && !isTouchOnView(event, btnNext)) {
            if (suppressTapAdvanceOnce) {
                suppressTapAdvanceOnce = false;
                return true;
            }
            goNext();
            return true;
        }

        return super.dispatchTouchEvent(event);
    }

    private boolean isTouchOnView(MotionEvent event, View view) {
        if (event == null || view == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        return rawX >= location[0]
                && rawX <= location[0] + view.getWidth()
                && rawY >= location[1]
                && rawY <= location[1] + view.getHeight();
    }

    private void goNext() {
        if (isCompleting) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastStepActionTime < STEP_ACTION_DEBOUNCE_MS) {
            return;
        }
        lastStepActionTime = now;

        if (currentStep < TOTAL_STEPS - 1) {
            updateStep(currentStep + 1);
        } else {
            completeOnboarding();
        }
    }

    private void updateStep(int step) {
        currentStep = step;
        if (icon != null) {
            icon.setImageResource(ICONS[step]);
        }
        if (title != null) {
            title.setText(TITLES[step]);
        }
        if (desc != null) {
            desc.setText(DESCS[step]);
        }
        if (btnNext != null) {
            btnNext.setText(step == TOTAL_STEPS - 1
                    ? getString(R.string.onboarding_start)
                    : getString(R.string.onboarding_next));
        }

        if (step > 0 && ttsHelper != null) {
            ttsHelper.speak(TTS_TEXTS[step]);
        }

        int active = 0xFFE53935;
        int inactive = 0xFF9E9E9E;
        if (dot0 != null) dot0.setBackgroundColor(step == 0 ? active : inactive);
        if (dot1 != null) dot1.setBackgroundColor(step == 1 ? active : inactive);
        if (dot2 != null) dot2.setBackgroundColor(step == 2 ? active : inactive);
        if (dot3 != null) dot3.setBackgroundColor(step == 3 ? active : inactive);
    }

    private void startIconAnimation() {
        if (icon == null) {
            return;
        }

        ObjectAnimator sx = ObjectAnimator.ofFloat(icon, "scaleX", 0.90f, 1.05f);
        sx.setRepeatCount(ValueAnimator.INFINITE);
        sx.setRepeatMode(ValueAnimator.REVERSE);

        ObjectAnimator sy = ObjectAnimator.ofFloat(icon, "scaleY", 0.90f, 1.05f);
        sy.setRepeatCount(ValueAnimator.INFINITE);
        sy.setRepeatMode(ValueAnimator.REVERSE);

        iconAnimSet = new AnimatorSet();
        iconAnimSet.playTogether(sx, sy);
        iconAnimSet.setDuration(1400);
        iconAnimSet.setInterpolator(new AccelerateDecelerateInterpolator());
        iconAnimSet.start();
    }

    private void completeOnboarding() {
        if (isCompleting || isFinishing()) {
            return;
        }
        isCompleting = true;
        SharedPrefsHelper.setFirstLaunchCompleted(this);
        SharedPrefsHelper.setTutorialCompleted(this, true);
        if (ttsHelper != null) {
            ttsHelper.speak(getString(R.string.onboarding_complete_tts));
        }
        startActivity(new Intent(this, MainActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (iconAnimSet != null) {
            iconAnimSet.cancel();
        }
        if (ttsHelper != null) {
            ttsHelper.shutdown();
            ttsHelper = null;
        }
    }
}
