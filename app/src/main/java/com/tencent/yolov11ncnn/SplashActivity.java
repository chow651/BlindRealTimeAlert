package com.tencent.yolov11ncnn;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 启动屏 - 每次启动都显示，约1.5秒后自动分流
 * 首次安装 → PixelWelcomeActivity（欢迎+引导）
 * 已安装   → MainActivity（直接进入）
 */
public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DURATION_MS = 1500;

    private Handler handler;
    private ValueAnimator scanLineAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        handler = new Handler(Looper.getMainLooper());
        startScanLineAnimation();

        handler.postDelayed(this::navigate, SPLASH_DURATION_MS);
    }

    private void startScanLineAnimation() {
        View scanLine = findViewById(R.id.splashScanLine);
        if (scanLine == null) return;

        scanLineAnimator = ValueAnimator.ofFloat(0f, 1f);
        scanLineAnimator.setDuration(1200);
        scanLineAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        scanLineAnimator.addUpdateListener(anim -> {
            float fraction = anim.getAnimatedFraction();
            int height = getWindow().getDecorView().getHeight();
            if (height > 0) scanLine.setTranslationY(fraction * height);
        });
        scanLineAnimator.start();
    }

    private void navigate() {
        if (isFinishing()) return;

        Intent intent;
        if (SharedPrefsHelper.isFirstLaunch(this)) {
            // 首次安装：进入欢迎+引导流程
            intent = new Intent(this, PixelWelcomeActivity.class);
        } else {
            // 已安装：直接进入主界面
            intent = new Intent(this, MainActivity.class);
        }
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanLineAnimator != null) {
            scanLineAnimator.cancel();
            scanLineAnimator = null;
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}
