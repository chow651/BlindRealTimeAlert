package com.tencent.yolov11ncnn;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/**
 * DetectionObject 单元测试
 * 测试检测对象的基本属性和计算方法
 */
public class DetectionObjectTest {

    private DetectionQueueManager.DetectionObject testObject;
    private float screenWidth = 1280.0f;
    private float screenHeight = 720.0f;

    @Before
    public void setUp() {
        // 创建测试对象：行人，位置(100, 100)，大小(50, 80)
        testObject = new DetectionQueueManager.DetectionObject(
            0,      // label: person
            0.9f,   // prob
            100f,   // x
            100f,   // y
            50f,    // width
            80f,    // height
            "人"    // className
        );
    }

    @Test
    public void testGetLabel() {
        assertEquals(0, testObject.getLabel());
    }

    @Test
    public void testGetProb() {
        assertEquals(0.9f, testObject.getProb(), 0.001f);
    }

    @Test
    public void testGetClassName() {
        assertEquals("人", testObject.getClassName());
    }

    @Test
    public void testGetCenterXNormalized() {
        // 中心X = (100 + 50/2) / 1280 = 125 / 1280 ≈ 0.0977
        float centerX = testObject.getCenterXNormalized(screenWidth);
        assertEquals(0.0977f, centerX, 0.001f);
    }

    @Test
    public void testGetCenterYNormalized() {
        // 中心Y = (100 + 80/2) / 720 = 140 / 720 ≈ 0.1944
        float centerY = testObject.getCenterYNormalized(screenHeight);
        assertEquals(0.1944f, centerY, 0.001f);
    }

    @Test
    public void testGetHeightRatio() {
        // 高度比 = 80 / 720 ≈ 0.1111
        float heightRatio = testObject.getHeightRatio(screenHeight);
        assertEquals(0.1111f, heightRatio, 0.001f);
    }

    @Test
    public void testGetAreaRatio() {
        // 面积比 = (50 * 80) / (1280 * 720) = 4000 / 921600 ≈ 0.00434
        float areaRatio = testObject.getAreaRatio(screenWidth, screenHeight);
        assertEquals(0.00434f, areaRatio, 0.0001f);
    }

    @Test
    public void testGetArea() {
        // 面积 = 50 * 80 = 4000
        float area = testObject.getArea();
        assertEquals(4000f, area, 0.1f);
    }

    @Test
    public void testGetPositionZone_Center() {
        // 创建中心位置的对象
        DetectionQueueManager.DetectionObject centerObj = 
            new DetectionQueueManager.DetectionObject(0, 0.9f, 500f, 100f, 50f, 80f, "人");
        
        RiskManager.PositionZone zone = centerObj.getPositionZone(screenWidth);
        assertEquals(RiskManager.PositionZone.CENTER, zone);
    }

    @Test
    public void testIsCloseRange_True() {
        // 测试近距离判定（高度比 > 0.5）
        DetectionQueueManager.DetectionObject closeObj = 
            new DetectionQueueManager.DetectionObject(0, 0.9f, 100f, 100f, 50f, 400f, "人");
        
        assertTrue(closeObj.isCloseRange(screenHeight));
    }

    @Test
    public void testIsCloseRange_False() {
        // 原测试对象高度比 < 0.5
        assertFalse(testObject.isCloseRange(screenHeight));
    }

    @Test
    public void testIsVehicle_True() {
        // 测试车辆类型
        DetectionQueueManager.DetectionObject car = 
            new DetectionQueueManager.DetectionObject(2, 0.9f, 100f, 100f, 50f, 80f, "汽车");
        assertTrue(car.isVehicle());
        
        DetectionQueueManager.DetectionObject bus = 
            new DetectionQueueManager.DetectionObject(5, 0.9f, 100f, 100f, 50f, 80f, "公交车");
        assertTrue(bus.isVehicle());
    }

    @Test
    public void testIsVehicle_False() {
        // 测试非车辆类型
        assertFalse(testObject.isVehicle());  // person
        
        DetectionQueueManager.DetectionObject bench = 
            new DetectionQueueManager.DetectionObject(13, 0.9f, 100f, 100f, 50f, 80f, "长椅");
        assertFalse(bench.isVehicle());
    }

    @Test
    public void testIsPerson_True() {
        assertTrue(testObject.isPerson());
    }

    @Test
    public void testIsPerson_False() {
        DetectionQueueManager.DetectionObject car = 
            new DetectionQueueManager.DetectionObject(2, 0.9f, 100f, 100f, 50f, 80f, "汽车");
        assertFalse(car.isPerson());
    }

    @Test
    public void testGetTop() {
        assertEquals(100f, testObject.getTop(), 0.1f);
    }

    @Test
    public void testGetBottom() {
        // bottom = y + height = 100 + 80 = 180
        assertEquals(180f, testObject.getBottom(), 0.1f);
    }

    @Test
    public void testMotionStateFlags() {
        // 测试运动状态标志的初始值
        assertFalse(testObject.isApproaching);
        assertFalse(testObject.isStationary);
        assertFalse(testObject.isMovingToCenter);
        assertFalse(testObject.isGroundObstacle);
        assertFalse(testObject.needsAnnouncement);
        assertFalse(testObject.needsVibration);
        assertEquals(0f, testObject.areaChangeRate, 0.001f);
    }

    @Test
    public void testGetRiskLevel() {
        RiskManager.RiskLevel level = testObject.getRiskLevel();
        assertEquals(RiskManager.RiskLevel.HIGH, level);  // person 是 HIGH
    }
}
