package com.tencent.yolov11ncnn;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.tencent.yolov11ncnn.model.DetectionState;
import com.tencent.yolov11ncnn.viewmodel.MainViewModel;

/**
 * 主界面 - View 层 (MVVM 架构)
 * 负责产品化主界面的 UI 显示和用户交互，业务逻辑由 ViewModel 处理。
 */
public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "MainActivity";
    public static final int REQUEST_CAMERA = 100;
    public static final int REQUEST_RECORD_AUDIO = 101;

    private static final long ORIENTATION_WARNING_INTERVAL = 4000;
    private static final long VOICE_BUBBLE_IDLE_DELAY_MS = 280;

    private MainViewModel viewModel;

    private SurfaceView cameraView;
    private TextView statusTextView;
    private TextView movementStatusView;
    private TextView tvVoiceHoldHint;
    private TextView tvVoiceBubble;
    private Button queryButton;
    private Button buttonHoldToTalk;
    private Button buttonSwitchCpuGpu;
    private View voiceStatusBubble;

    private boolean isPressingVoiceArea = false;
    private boolean pendingVoiceStart = false;
    private boolean currentVoiceAvailable = true;
    private boolean currentVoiceListening = false;
    private int activeVoicePointerId = MotionEvent.INVALID_POINTER_ID;
    private long lastOrientationWarningTime = 0;
    private DetectionState latestDetectionState;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable bubbleIdleRunnable;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        initializeUI();
        observeViewModel();
        viewModel.loadModel(getAssets(), 0, 1);
    }

    private void initializeUI() {
        cameraView = findViewById(R.id.cameraview);
        if (cameraView == null) {
            Toast.makeText(this, "Camera view init failed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        cameraView.getHolder().setFormat(PixelFormat.RGBA_8888);
        cameraView.getHolder().addCallback(this);

        statusTextView = findViewById(R.id.statusText);
        movementStatusView = findViewById(R.id.movementStatusButton);
        buttonHoldToTalk = findViewById(R.id.buttonHoldToTalk);
        tvVoiceHoldHint = findViewById(R.id.tvVoiceHoldHint);
        voiceStatusBubble = findViewById(R.id.voiceStatusBubble);
        tvVoiceBubble = findViewById(R.id.tvVoiceBubble);
        buttonSwitchCpuGpu = findViewById(R.id.buttonSwitchCpuGpu);

        Button btnSwitch = findViewById(R.id.buttonSwitchCamera);
        if (btnSwitch != null) {
            btnSwitch.setOnClickListener(v -> viewModel.switchCamera());
        }

        if (buttonSwitchCpuGpu != null) {
            buttonSwitchCpuGpu.setOnClickListener(v -> {
                int nextCpuGpu = viewModel.getCurrentCpuGpu() == 0 ? 1 : 0;
                viewModel.toggleCpuGpu(nextCpuGpu, getAssets());
            });
        }

        queryButton = findViewById(R.id.buttonActiveQuery);
        if (queryButton != null) {
            queryButton.setOnClickListener(v -> viewModel.performSceneQuery());
        }

        if (buttonHoldToTalk != null) {
            buttonHoldToTalk.setOnTouchListener(this::handleVoiceHoldTouch);
        }

        updateMovementStatusChip(false);
        updateCpuGpuControls(viewModel.getCurrentCpuGpu());
        applyVoiceBubbleIdle();
        updateVoiceHoldHintByState();

        Log.d(TAG, "开始初始化讯飞离线语音识别");
        viewModel.initializeOfflineSpeechRecognition(() -> Log.d(TAG, "讯飞离线语音识别初始化完成"));
    }

    private void observeViewModel() {
        viewModel.getDetectionState().observe(this, this::updateDetectionState);
        viewModel.getOrientationValid().observe(this, this::updateOrientationState);
        viewModel.getAnnouncementEvent().observe(this, event -> {
            // 主界面使用状态提示区承接辅助文案。
        });
        viewModel.getVoiceState().observe(this, this::updateVoiceState);
        viewModel.getComputeModeState().observe(this, this::updateCpuGpuControls);
    }

    private void updateDetectionState(DetectionState state) {
        latestDetectionState = state;
        if (statusTextView != null) {
            statusTextView.setText(state.getStatusText());
        }
        updateMovementStatusChip(state.isUserMoving());
        if (!currentVoiceListening && !isPressingVoiceArea && !pendingVoiceStart) {
            updateVoiceHoldHintByState();
        }
    }

    private void updateMovementStatusChip(boolean isMoving) {
        if (movementStatusView != null) {
            movementStatusView.setText(isMoving ? "状态: 移动" : "状态: 静止");
        }
    }

    private void updateCpuGpuControls(Integer cpuGpu) {
        if (cpuGpu == null) {
            return;
        }
        if (buttonSwitchCpuGpu != null) {
            buttonSwitchCpuGpu.setText(cpuGpu == 0 ? "当前模式：CPU" : "当前模式：GPU");
        }
    }

    private void updateOrientationState(Boolean isValid) {
        if (!isValid) {
            long now = System.currentTimeMillis();
            if (now - lastOrientationWarningTime > ORIENTATION_WARNING_INTERVAL) {
                lastOrientationWarningTime = now;
                if (!currentVoiceListening && !isPressingVoiceArea && !pendingVoiceStart) {
                    updateVoiceHoldHintByState();
                }
            }
        }
    }

    private void updateVoiceState(com.tencent.yolov11ncnn.model.VoiceState state) {
        currentVoiceAvailable = state.isAvailable();
        currentVoiceListening = state.isListening();

        if (!currentVoiceAvailable) {
            pendingVoiceStart = false;
            isPressingVoiceArea = false;
            resetVoiceTouchTracking();
        }

        updateVoiceHoldHintByState();
        boolean showListeningState = currentVoiceAvailable
                && (currentVoiceListening || isPressingVoiceArea || pendingVoiceStart);
        updateVoiceBubbleState(showListeningState, currentVoiceAvailable);
    }

    private void updateVoiceBubbleState(boolean isListening, boolean isAvailable) {
        if (voiceStatusBubble == null || tvVoiceBubble == null) {
            return;
        }

        if (bubbleIdleRunnable != null) {
            uiHandler.removeCallbacks(bubbleIdleRunnable);
            bubbleIdleRunnable = null;
        }

        if (!isAvailable) {
            voiceStatusBubble.setBackgroundResource(R.drawable.bg_voice_badge_unavailable_neo);
            tvVoiceBubble.setText("语音\n关闭");
            tvVoiceBubble.setTextColor(0xFFFFFFFF);
            return;
        }

        if (isListening) {
            applyVoiceBubbleListening();
            return;
        }

        bubbleIdleRunnable = this::applyVoiceBubbleIdle;
        uiHandler.postDelayed(bubbleIdleRunnable, VOICE_BUBBLE_IDLE_DELAY_MS);
    }

    private void applyVoiceBubbleListening() {
        if (voiceStatusBubble == null || tvVoiceBubble == null) {
            return;
        }
        voiceStatusBubble.setBackgroundResource(R.drawable.bg_voice_badge_listening_neo);
        tvVoiceBubble.setText("语音\n聆听");
        tvVoiceBubble.setTextColor(0xFFFFFFFF);
    }

    private void applyVoiceBubbleIdle() {
        if (voiceStatusBubble == null || tvVoiceBubble == null) {
            return;
        }
        voiceStatusBubble.setBackgroundResource(R.drawable.bg_voice_badge_idle_neo);
        tvVoiceBubble.setText("语音\n待命");
        tvVoiceBubble.setTextColor(0xFF1A1A2E);
    }

    private boolean handleVoiceHoldTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isPressingVoiceArea || activeVoicePointerId != MotionEvent.INVALID_POINTER_ID) {
                    return true;
                }
                activeVoicePointerId = event.getPointerId(0);
                v.setPressed(true);
                startVoiceHoldSession();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerId(0) != activeVoicePointerId) {
                    return true;
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                int actionIndex = event.getActionIndex();
                if (event.getPointerId(actionIndex) == activeVoicePointerId) {
                    v.setPressed(false);
                    stopVoiceHoldSession();
                    resetVoiceTouchTracking();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (activeVoicePointerId == MotionEvent.INVALID_POINTER_ID) {
                    return true;
                }
                v.setPressed(false);
                stopVoiceHoldSession();
                resetVoiceTouchTracking();
                v.performClick();
                return true;

            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                stopVoiceHoldSession();
                resetVoiceTouchTracking();
                return true;

            default:
                return true;
        }
    }

    private void startVoiceHoldSession() {
        Log.d(TAG, "startVoiceHoldSession called, currentVoiceAvailable=" + currentVoiceAvailable);
        if (!currentVoiceAvailable) {
            Log.w(TAG, "Voice not available, skipping voice session");
            updateVoiceHoldHintByState();
            updateVoiceBubbleState(false, false);
            return;
        }

        if (isPressingVoiceArea || pendingVoiceStart) {
            return;
        }

        isPressingVoiceArea = true;
        pendingVoiceStart = false;
        updateVoiceHoldHintByState();
        updateVoiceBubbleState(true, true);

        if (!hasAudioPermission()) {
            pendingVoiceStart = true;
            requestAudioPermission();
            return;
        }

        startVoiceListeningIfNeeded();
    }

    private void stopVoiceHoldSession() {
        if (!isPressingVoiceArea && !pendingVoiceStart) {
            return;
        }

        isPressingVoiceArea = false;
        pendingVoiceStart = false;
        currentVoiceListening = false;
        stopVoiceListeningIfNeeded();
        updateVoiceHoldHintByState();
        updateVoiceBubbleState(false, currentVoiceAvailable);
    }

    private void startVoiceListeningIfNeeded() {
        Log.d(TAG, "startVoiceListeningIfNeeded called");
        viewModel.startVoiceListening();
    }

    private void stopVoiceListeningIfNeeded() {
        Log.d(TAG, "stopVoiceListeningIfNeeded called");
        viewModel.stopVoiceListening();
    }

    private void resetVoiceTouchTracking() {
        activeVoicePointerId = MotionEvent.INVALID_POINTER_ID;
    }

    private String buildIdlePromptText() {
        if (!currentVoiceAvailable) {
            return getString(R.string.voice_service_unavailable_title);
        }
        if (latestDetectionState == null) {
            return "当前提示：持续检测前方环境，可点击主动查询或按住说话";
        }
        if (!latestDetectionState.isOrientationValid()) {
            return "当前提示：请保持手机竖直，摄像头朝向前方";
        }
        if (latestDetectionState.getObjectCount() > 0) {
            return "当前提示：已检测到" + latestDetectionState.getObjectCount() + "个目标，可点击主动查询或按住说话";
        }
        if (latestDetectionState.isUserMoving()) {
            return "当前提示：环境持续检测中，移动时将自动提醒";
        }
        return "当前提示：当前较为静止，可点击主动查询或按住说话";
    }

    private void updateVoiceHoldHintByState() {
        if (tvVoiceHoldHint == null) {
            return;
        }

        if (buttonHoldToTalk != null) {
            buttonHoldToTalk.setEnabled(currentVoiceAvailable);
        }

        if (!currentVoiceAvailable) {
            tvVoiceHoldHint.setText(getString(R.string.voice_service_unavailable_title));
            if (buttonHoldToTalk != null) {
                buttonHoldToTalk.setText("语音不可用");
            }
            return;
        }

        if (isPressingVoiceArea) {
            tvVoiceHoldHint.setText("正在聆听您的指令...");
            if (buttonHoldToTalk != null) {
                buttonHoldToTalk.setText("松开结束");
            }
            return;
        }

        tvVoiceHoldHint.setText(buildIdlePromptText());
        if (buttonHoldToTalk != null) {
            buttonHoldToTalk.setText("按住说话");
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        viewModel.setScreenSize(width, height);
        viewModel.setOutputWindow(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        viewModel.setOutputWindow(null);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                showPermissionRationale();
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.CAMERA }, REQUEST_CAMERA);
            }
        } else {
            viewModel.startDetection();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        pendingVoiceStart = false;
        isPressingVoiceArea = false;
        currentVoiceListening = false;
        resetVoiceTouchTracking();
        viewModel.stopVoiceListening();
        viewModel.stopDetection();
        if (bubbleIdleRunnable != null) {
            uiHandler.removeCallbacks(bubbleIdleRunnable);
            bubbleIdleRunnable = null;
        }
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(this,
                new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_RECORD_AUDIO);
    }

    private void showPermissionRationale() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.camera_permission_title)
                .setMessage(R.string.camera_permission_message)
                .setPositiveButton(R.string.camera_permission_grant, (dialog, which) -> ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.CAMERA }, REQUEST_CAMERA))
                .setNegativeButton(R.string.camera_permission_cancel, (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                viewModel.startDetection();
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (requestCode == REQUEST_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingVoiceStart && isPressingVoiceArea) {
                pendingVoiceStart = false;
                startVoiceListeningIfNeeded();
            } else {
                pendingVoiceStart = false;
                updateVoiceHoldHintByState();
            }
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "MainActivity onDestroy() called");
        super.onDestroy();
        if (viewModel != null) {
            Log.d(TAG, "Calling viewModel.release()");
            viewModel.release();
            Log.d(TAG, "viewModel.release() completed");
        }
        uiHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "MainActivity onDestroy() completed");
    }
}
