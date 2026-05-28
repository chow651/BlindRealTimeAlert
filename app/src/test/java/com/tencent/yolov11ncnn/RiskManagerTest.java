package com.tencent.yolov11ncnn;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * RiskManager 单元测试
 * 测试风险等级判定、方位划分、冷却时间等功能
 */
public class RiskManagerTest {

    @Test
    public void testGetRiskLevel_Person() {
        // 测试行人的风险等级
        RiskManager.RiskLevel level = RiskManager.getRiskLevel(0);
        assertEquals(RiskManager.RiskLevel.HIGH, level);
    }

    @Test
    public void testGetRiskLevel_Car() {
        // 测试汽车的风险等级
        RiskManager.RiskLevel level = RiskManager.getRiskLevel(2);
        assertEquals(RiskManager.RiskLevel.HIGH, level);
    }

    @Test
    public void testGetRiskLevel_Bicycle() {
        // 测试自行车的风险等级
        RiskManager.RiskLevel level = RiskManager.getRiskLevel(1);
        assertEquals(RiskManager.RiskLevel.HIGH, level);
    }

    @Test
    public void testGetPositionZone_Center() {
        // 测试正前方区域判定 (30%-60%)
        RiskManager.PositionZone zone = RiskManager.getPositionZone(0.45f);
        assertEquals(RiskManager.PositionZone.CENTER, zone);
    }

    @Test
    public void testGetPositionZone_LeftFront() {
        // 测试左前方区域判定 (12%-30%)
        RiskManager.PositionZone zone = RiskManager.getPositionZone(0.20f);
        assertEquals(RiskManager.PositionZone.LEFT_FRONT, zone);
    }

    @Test
    public void testGetPositionZone_RightFront() {
        // 测试右前方区域判定 (60%-82%)
        RiskManager.PositionZone zone = RiskManager.getPositionZone(0.70f);
        assertEquals(RiskManager.PositionZone.RIGHT_FRONT, zone);
    }

    @Test
    public void testGetPositionZone_LeftSide() {
        // 测试左侧区域判定 (0-15%)
        RiskManager.PositionZone zone = RiskManager.getPositionZone(0.05f);
        assertEquals(RiskManager.PositionZone.LEFT_SIDE, zone);
    }

    @Test
    public void testGetPositionZone_RightSide() {
        // 测试右侧区域判定 (85%-100%)
        RiskManager.PositionZone zone = RiskManager.getPositionZone(0.90f);
        assertEquals(RiskManager.PositionZone.RIGHT_SIDE, zone);
    }

    @Test
    public void testGetPositionZone_Boundary() {
        // 测试边界值
        assertEquals(RiskManager.PositionZone.LEFT_SIDE, RiskManager.getPositionZone(0.0f));
        assertEquals(RiskManager.PositionZone.RIGHT_SIDE, RiskManager.getPositionZone(1.0f));
        assertEquals(RiskManager.PositionZone.CENTER, RiskManager.getPositionZone(0.5f));
    }

    @Test
    public void testIsCloseRange_True() {
        // 测试近距离判定 (高度比 > 0.5)
        assertTrue(RiskManager.isCloseRange(0.6f));
        assertTrue(RiskManager.isCloseRange(0.8f));
        assertTrue(RiskManager.isCloseRange(1.0f));
    }

    @Test
    public void testIsCloseRange_False() {
        // 测试非近距离判定 (高度比 <= 0.5)
        assertFalse(RiskManager.isCloseRange(0.1f));
        assertFalse(RiskManager.isCloseRange(0.3f));
        assertFalse(RiskManager.isCloseRange(0.5f));
    }

    @Test
    public void testGetCooldownTime_Critical() {
        // 测试极危风险的冷却时间
        long cooldown = RiskManager.getCooldownTime(RiskManager.RiskLevel.CRITICAL);
        assertEquals(1500L, cooldown);
    }

    @Test
    public void testGetCooldownTime_High() {
        // 测试高危风险的冷却时间
        long cooldown = RiskManager.getCooldownTime(RiskManager.RiskLevel.HIGH);
        assertEquals(4000L, cooldown);
    }

    @Test
    public void testGetCooldownTime_Normal() {
        // 测试普通风险的冷却时间
        long cooldown = RiskManager.getCooldownTime(RiskManager.RiskLevel.NORMAL);
        assertEquals(6000L, cooldown);
    }

    @Test
    public void testComparePriority_Critical_vs_High() {
        // 测试优先级比较：极危 > 高危
        int result = RiskManager.comparePriority(
            RiskManager.RiskLevel.CRITICAL, 
            RiskManager.RiskLevel.HIGH
        );
        assertTrue(result < 0);  // CRITICAL 优先级更高
    }

    @Test
    public void testComparePriority_High_vs_Normal() {
        // 测试优先级比较：高危 > 普通
        int result = RiskManager.comparePriority(
            RiskManager.RiskLevel.HIGH, 
            RiskManager.RiskLevel.NORMAL
        );
        assertTrue(result < 0);  // HIGH 优先级更高
    }

    @Test
    public void testComparePriority_Same() {
        // 测试优先级比较：相同等级
        int result = RiskManager.comparePriority(
            RiskManager.RiskLevel.HIGH, 
            RiskManager.RiskLevel.HIGH
        );
        assertEquals(0, result);
    }

    @Test
    public void testDetermineRiskLevel_ApproachingVehicle() {
        // 测试正在接近的车辆 → 极危
        RiskManager.RiskLevel level = RiskManager.determineRiskLevel(
            2,  // car
            RiskManager.PositionZone.CENTER,
            true,   // isCloseRange
            true,   // isApproaching
            false,  // isMovingToCenter
            false   // isGroundObstacle
        );
        assertEquals(RiskManager.RiskLevel.CRITICAL, level);
    }

    @Test
    public void testDetermineRiskLevel_CenterPerson() {
        // 测试正前方行人 → 高危
        RiskManager.RiskLevel level = RiskManager.determineRiskLevel(
            0,  // person
            RiskManager.PositionZone.CENTER,
            false,  // isCloseRange
            false,  // isApproaching
            false,  // isMovingToCenter
            false   // isGroundObstacle
        );
        assertEquals(RiskManager.RiskLevel.HIGH, level);
    }

    @Test
    public void testDetermineRiskLevel_EdgeObstacle() {
        // 测试边缘障碍物 → 普通
        RiskManager.RiskLevel level = RiskManager.determineRiskLevel(
            13,  // bench
            RiskManager.PositionZone.LEFT_SIDE,
            false,  // isCloseRange
            false,  // isApproaching
            false,  // isMovingToCenter
            false   // isGroundObstacle
        );
        assertEquals(RiskManager.RiskLevel.NORMAL, level);
    }

    @Test
    public void testIsGroundObstacle_True() {
        // 测试地面障碍物判定
        assertTrue(RiskManager.isGroundObstacle(13, 0.95f, 0.05f));  // bench, 底部接近屏幕底部
    }

    @Test
    public void testIsGroundObstacle_False() {
        // 测试非地面障碍物
        assertFalse(RiskManager.isGroundObstacle(0, 0.5f, 0.05f));   // person, 不在底部
        assertFalse(RiskManager.isGroundObstacle(13, 0.5f, 0.05f));  // bench, 不在底部
    }

    @Test
    public void testIsInValidSpatialRange_Valid() {
        // 测试有效空间范围
        assertTrue(RiskManager.isInValidSpatialRange(0, 0.5f, 0.02f));  // 中心位置，合理面积
    }

    @Test
    public void testIsInValidSpatialRange_TooSmall() {
        // 测试面积过小
        assertFalse(RiskManager.isInValidSpatialRange(0, 0.5f, 0.0001f));
    }

    @Test
    public void testIsInValidSpatialRange_EdgeAndSmall() {
        // 测试边缘且面积小
        assertFalse(RiskManager.isInValidSpatialRange(0, 0.05f, 0.005f));
    }
}
