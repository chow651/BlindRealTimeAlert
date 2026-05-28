package com.tencent.yolov11ncnn;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * 手机姿态检测器 - 检测手机是否正对前方
 * 防止手机放口袋或斜拿时产生误检测
 */
public class OrientationSensorManager implements SensorEventListener {
    private static final String TAG = "OrientationSensor";
    
    private final SensorManager sensorManager;
    private Sensor gravitySensor;
    private Sensor accelerometer;
    
    private boolean isOrientationValid = true;
    private OrientationListener listener;
    private volatile float currentPitchDegrees = 0f;
    private volatile float currentRollDegrees = 0f;
    
    private final float[] gravity = new float[3];  // 低通滤波后的重力向量
    private static final float ALPHA = 0.85f;  // 低通滤波系数（提高以降低抖动误判）

    private final OrientationStateMachine stateMachine = new OrientationStateMachine();

    public interface OrientationListener {
        void onOrientationChanged(float pitch, float roll);
        void onOrientationInvalid(float pitch, float roll);
    }

    public OrientationSensorManager(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            // 优先使用系统融合后的重力传感器，抗动态加速度干扰更好。
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    public void setListener(OrientationListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (sensorManager != null) {
            Sensor sensor = gravitySensor != null ? gravitySensor : accelerometer;
            if (sensor != null) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    public boolean isOrientationValid() {
        return isOrientationValid;
    }

    public float getCurrentPitchDegrees() {
        return currentPitchDegrees;
    }

    public float getCurrentRollDegrees() {
        return currentRollDegrees;
    }

    public void release() {
        stop();
        listener = null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int sensorType = event.sensor.getType();
        if (sensorType != Sensor.TYPE_ACCELEROMETER && sensorType != Sensor.TYPE_GRAVITY) {
            return;
        }

        if (sensorType == Sensor.TYPE_GRAVITY) {
            gravity[0] = event.values[0];
            gravity[1] = event.values[1];
            gravity[2] = event.values[2];
        } else {
            // 低通滤波平滑加速度计数据
            gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * event.values[0];
            gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * event.values[1];
            gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * event.values[2];
        }

        float absZ = Math.abs(gravity[2]);
        updateAttitudeFromGravity();
        boolean stateChanged = stateMachine.updateByAbsZ(absZ);
        if (stateChanged) {
            isOrientationValid = stateMachine.isOrientationValid();

            Log.i(TAG, "姿态状态变化: " + (isOrientationValid ? "有效" : "无效") +
                    String.format(" (Z=%.2f, 阈值=%.1f/%.1f)",
                            gravity[2],
                            OrientationStateMachine.Z_AXIS_THRESHOLD_LOW,
                            OrientationStateMachine.Z_AXIS_THRESHOLD_HIGH));

            if (listener != null) {
                if (isOrientationValid) {
                    listener.onOrientationChanged(currentPitchDegrees, currentRollDegrees);
                } else {
                    listener.onOrientationInvalid(currentPitchDegrees, currentRollDegrees);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void updateAttitudeFromGravity() {
        float gx = gravity[0];
        float gy = gravity[1];
        float gz = gravity[2];
        float norm = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (norm < 0.001f) {
            return;
        }

        gx /= norm;
        gy /= norm;
        gz /= norm;

        // 俯仰：屏幕法线与水平面的夹角近似，范围约 [-90, 90]
        currentPitchDegrees = (float) Math.toDegrees(Math.asin(clamp(gz, -1f, 1f)));

        // 横滚：用于估计画面左右方位偏转（右倾为正）
        float roll = (float) Math.toDegrees(Math.atan2(gx, -gy));
        if (roll > 90f) {
            roll -= 180f;
        } else if (roll < -90f) {
            roll += 180f;
        }
        currentRollDegrees = roll;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    static final class OrientationStateMachine {
        // 相比上一版将可容忍倾角约收紧 30%，避免“几乎看不到前方仍判定有效”。
        // 高阈值约对应 49°，低阈值约对应 44°（从垂直方向计）。
        static final float Z_AXIS_THRESHOLD_HIGH = 7.4f;  // 有效 -> 无效
        static final float Z_AXIS_THRESHOLD_LOW = 6.8f;   // 无效 -> 有效（滞后）
        private static final int TRANSITION_CONFIRM_SAMPLES = 3;

        private boolean orientationValid = true;
        private Boolean pendingTargetValid = null;
        private int pendingCount = 0;

        boolean updateByAbsZ(float absZ) {
            boolean candidateValid = orientationValid
                    ? absZ < Z_AXIS_THRESHOLD_HIGH
                    : absZ < Z_AXIS_THRESHOLD_LOW;

            if (candidateValid == orientationValid) {
                resetPending();
                return false;
            }

            if (pendingTargetValid == null || pendingTargetValid != candidateValid) {
                pendingTargetValid = candidateValid;
                pendingCount = 1;
                return false;
            }

            pendingCount++;
            if (pendingCount < TRANSITION_CONFIRM_SAMPLES) {
                return false;
            }

            orientationValid = candidateValid;
            resetPending();
            return true;
        }

        boolean isOrientationValid() {
            return orientationValid;
        }

        private void resetPending() {
            pendingTargetValid = null;
            pendingCount = 0;
        }
    }
}
