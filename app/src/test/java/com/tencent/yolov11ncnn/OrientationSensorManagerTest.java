package com.tencent.yolov11ncnn;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * OrientationSensorManager 姿态判定测试
 */
public class OrientationSensorManagerTest {

    @Test
    public void transientSpikeShouldNotFlipToInvalid() {
        OrientationSensorManager.OrientationStateMachine machine =
                new OrientationSensorManager.OrientationStateMachine();

        // 正常姿态
        machine.updateByAbsZ(6.0f);
        assertTrue(machine.isOrientationValid());

        // 单次尖峰（例如走路瞬时加速度），不应立即翻转为无效
        machine.updateByAbsZ(9.4f);
        assertTrue(machine.isOrientationValid());
    }

    @Test
    public void shouldRecoverFromInvalidAtModerateTilt() {
        OrientationSensorManager.OrientationStateMachine machine =
                new OrientationSensorManager.OrientationStateMachine();

        // 持续高 Z 值进入无效
        machine.updateByAbsZ(9.6f);
        machine.updateByAbsZ(9.6f);
        machine.updateByAbsZ(9.6f);
        assertFalse(machine.isOrientationValid());

        // 回到前向可用区（无需完全垂直）后应能恢复
        machine.updateByAbsZ(6.5f);
        machine.updateByAbsZ(6.5f);
        machine.updateByAbsZ(6.5f);
        assertTrue(machine.isOrientationValid());
    }
}
