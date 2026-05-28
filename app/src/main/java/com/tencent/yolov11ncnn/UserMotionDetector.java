package com.tencent.yolov11ncnn;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * 用户运动检测器 - 通过加速度计和陀螺仪判断用户是在走动还是静止
 * 静止时只播报紧急情况，避免反复播报周围静止障碍物
 */
public class UserMotionDetector implements SensorEventListener {
    private static final String TAG = "MotionDiag";
    private static final boolean ENABLE_MOTION_DIAG = BuildConfig.DEBUG;
    private static final long MOTION_DIAG_INTERVAL_MS = 300L;

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor gyroscope;

    private boolean isMoving = false;
    private long lastMotionTime = 0;
    private long lastMotionDiagLogTime = 0;
    private boolean lastReportedMoving = true;
    private boolean lastReportedQueryMoving = true;
    private final QueryMotionClassifier queryMotionClassifier;

    // 阈值配置
    private static final float ACCEL_THRESHOLD = 1.2f;   // 加速度阈值 m/s²
    private static final float GYRO_THRESHOLD = 0.4f;    // 角速度阈值 rad/s
    private static final long MOTION_TIMEOUT = 2000;     // 2秒无运动判定为静止

    public UserMotionDetector(SensorManager sensorManager) {
        this.sensorManager = sensorManager;
        this.accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        long now = System.currentTimeMillis();
        this.queryMotionClassifier = new QueryMotionClassifier(now);
        // 初始假设在移动，避免启动时漏报
        this.lastMotionTime = now;
        this.isMoving = true;
    }

    public void start() {
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = System.currentTimeMillis();
        float accelMagnitude = Float.NaN;
        float gyroMagnitude = Float.NaN;
        int sensorType = event.sensor.getType();

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            // 合加速度减去重力，静止时接近0
            accelMagnitude = (float) Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH;
            queryMotionClassifier.onAccelSample(Math.abs(accelMagnitude), now);

            if (Math.abs(accelMagnitude) > ACCEL_THRESHOLD) {
                isMoving = true;
                lastMotionTime = now;
            }
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            gyroMagnitude = (float) Math.sqrt(x * x + y * y + z * z);
            queryMotionClassifier.onGyroSample(gyroMagnitude, now);

            if (gyroMagnitude > GYRO_THRESHOLD) {
                isMoving = true;
                lastMotionTime = now;
            }
        }

        // 超时判定为静止
        if (now - lastMotionTime > MOTION_TIMEOUT) {
            isMoving = false;
        }

        if (ENABLE_MOTION_DIAG) {
            boolean queryMoving = queryMotionClassifier.isMovingForQuery(now);
            boolean movingChanged = (isMoving != lastReportedMoving);
            boolean queryChanged = (queryMoving != lastReportedQueryMoving);
            if (movingChanged || queryChanged || now - lastMotionDiagLogTime >= MOTION_DIAG_INTERVAL_MS) {
                String sensorName = sensorType == Sensor.TYPE_ACCELEROMETER
                        ? "ACC" : (sensorType == Sensor.TYPE_GYROSCOPE ? "GYR" : String.valueOf(sensorType));
                long stillMs = now - lastMotionTime;
                long queryStillMs = queryMotionClassifier.getLastWalkingLikeMotionAgeMs(now);
                String accelText = Float.isNaN(accelMagnitude) ? "-" : String.format(java.util.Locale.US, "%.3f", accelMagnitude);
                String gyroText = Float.isNaN(gyroMagnitude) ? "-" : String.format(java.util.Locale.US, "%.3f", gyroMagnitude);
                Log.d(TAG, "sensor=" + sensorName
                        + " accelMag=" + accelText
                        + " gyroMag=" + gyroText
                        + " movingRaw=" + (isMoving ? 1 : 0)
                        + " movingQuery=" + (queryMoving ? 1 : 0)
                        + " stillMsRaw=" + stillMs
                        + " stillMsQuery=" + queryStillMs
                        + " qAccelHits=" + queryMotionClassifier.getAccelHitCount(now)
                        + " qGyroHits=" + queryMotionClassifier.getGyroHitCount(now));
                lastMotionDiagLogTime = now;
                lastReportedMoving = isMoving;
                lastReportedQueryMoving = queryMoving;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    public boolean isUserMoving() {
        return isMoving;
    }

    public boolean isUserMovingForQuery() {
        return queryMotionClassifier.isMovingForQuery(System.currentTimeMillis());
    }
}
