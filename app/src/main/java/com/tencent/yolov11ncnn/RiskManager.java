package com.tencent.yolov11ncnn;

import java.util.HashSet;
import java.util.Set;

/**
 * 风险管理器 - 负责方位判定、风险等级、冷却时间等
 */
public class RiskManager {

    // 方位区域，把画面横向分成5块
    public enum PositionZone {
        LEFT_SIDE("左侧"),
        LEFT_FRONT("左前方"),
        CENTER("正前方"),
        RIGHT_FRONT("右前方"),
        RIGHT_SIDE("右侧");

        private final String displayName;
        
        PositionZone(String displayName) { 
            this.displayName = displayName; 
        }
        
        public String getDisplayName() { 
            return displayName; 
        }
    }

    // 风险等级：极危>高危>普通
    public enum RiskLevel {
        CRITICAL(0, "极危", 100L),
        HIGH(1, "高危", 300L),
        NORMAL(2, "普通", 600L);

        private final int priority;
        private final String displayName;
        private final long confirmTime;

        RiskLevel(int priority, String displayName, long confirmTime) {
            this.priority = priority;
            this.displayName = displayName;
            this.confirmTime = confirmTime;
        }
        
        public int getPriority() { return priority; }
        public String getDisplayName() { return displayName; }
        public long getConfirmTime() { return confirmTime; }
    }

    public static final long EMERGENCY_COOLDOWN = 800L;

    // 方位边界值（对称化，正前方在画面中心）
    private static final float BOUNDARY_LEFT_SIDE = 0.15f;       // 左侧：0.0-0.15
    private static final float BOUNDARY_LEFT_FRONT = 0.35f;      // 左前方：0.15-0.35
    private static final float BOUNDARY_RIGHT_FRONT = 0.65f;     // 正前方：0.35-0.65（对称）
    private static final float BOUNDARY_RIGHT_SIDE = 0.85f;      // 右前方：0.65-0.85
                                                                  // 右侧：0.85-1.0
    private static final float HYSTERESIS = 0.03f;  // 防抖用的滞后区间

    // 高风险类型：人、自行车、汽车、摩托、公交、卡车
    private static final Set<Integer> HIGH_RISK_LABELS = new HashSet<>();
    static {
        HIGH_RISK_LABELS.add(0);   // person
        HIGH_RISK_LABELS.add(1);   // bicycle
        HIGH_RISK_LABELS.add(2);   // car
        HIGH_RISK_LABELS.add(3);   // motorcycle
        HIGH_RISK_LABELS.add(5);   // bus
        HIGH_RISK_LABELS.add(7);   // truck
    }

    // 根据x坐标判断方位
    public static PositionZone getPositionZone(float x) {
        if (x < BOUNDARY_LEFT_SIDE) return PositionZone.LEFT_SIDE;
        if (x < BOUNDARY_LEFT_FRONT) return PositionZone.LEFT_FRONT;
        if (x < BOUNDARY_RIGHT_FRONT) return PositionZone.CENTER;
        if (x < BOUNDARY_RIGHT_SIDE) return PositionZone.RIGHT_FRONT;
        return PositionZone.RIGHT_SIDE;
    }

    // 带防抖的方位判定，避免边界处频繁切换
    public static PositionZone getPositionZone(float x, PositionZone lastZone) {
        if (lastZone == null) {
            return getPositionZone(x);
        }

        switch (lastZone) {
            case LEFT_SIDE:
                if (x > BOUNDARY_LEFT_SIDE + HYSTERESIS) return PositionZone.LEFT_FRONT;
                return PositionZone.LEFT_SIDE;
                
            case LEFT_FRONT:
                if (x < BOUNDARY_LEFT_SIDE - HYSTERESIS) return PositionZone.LEFT_SIDE;
                if (x > BOUNDARY_LEFT_FRONT + HYSTERESIS) return PositionZone.CENTER;
                return PositionZone.LEFT_FRONT;
                
            case CENTER:
                if (x < BOUNDARY_LEFT_FRONT - HYSTERESIS) return PositionZone.LEFT_FRONT;
                if (x > BOUNDARY_RIGHT_FRONT + HYSTERESIS) return PositionZone.RIGHT_FRONT;
                return PositionZone.CENTER;
                
            case RIGHT_FRONT:
                if (x < BOUNDARY_RIGHT_FRONT - HYSTERESIS) return PositionZone.CENTER;
                if (x > BOUNDARY_RIGHT_SIDE + HYSTERESIS) return PositionZone.RIGHT_SIDE;
                return PositionZone.RIGHT_FRONT;
                
            case RIGHT_SIDE:
                if (x < BOUNDARY_RIGHT_SIDE - HYSTERESIS) return PositionZone.RIGHT_FRONT;
                return PositionZone.RIGHT_SIDE;
        }
        return PositionZone.CENTER;
    }

    public static RiskLevel getRiskLevel(int label) {
        if (HIGH_RISK_LABELS.contains(label)) {
            return RiskLevel.HIGH;
        }
        return RiskLevel.NORMAL;
    }

    // 综合判定风险：逼近或近距离=极危，高风险类型在正前方=高危
    public static RiskLevel determineRiskLevel(int label, PositionZone zone, 
            boolean isCloseRange, boolean isLooming, boolean isMovingToCenter, boolean isGround) {
        
        if (isLooming || isCloseRange) {
            return RiskLevel.CRITICAL;
        }
        
        if (HIGH_RISK_LABELS.contains(label) && zone == PositionZone.CENTER) {
            return RiskLevel.HIGH;
        }
        
        return RiskLevel.NORMAL;
    }

    public static String getAnnouncementPrefix(RiskLevel level) {
        return (level == RiskLevel.CRITICAL) ? "危险！" : "";
    }

    public static int comparePriority(RiskLevel l1, RiskLevel l2) {
        return Integer.compare(l1.getPriority(), l2.getPriority());
    }

    public static long getConfirmTime(RiskLevel level) {
        return level.getConfirmTime();
    }

    // 不同风险等级的冷却时间
    public static long getCooldownTime(RiskLevel level) {
        switch (level) {
            case CRITICAL: return 1500L;
            case HIGH: return 4000L;
            default: return 6000L;
        }
    }
    
    // 过滤边缘小目标
    public static boolean isInValidSpatialRange(int label, float cx, float area) {
        boolean isAtEdge = cx < 0.15f || cx > 0.85f;
        if (isAtEdge && area < 0.008f) {
            return false;
        }
        return cx > 0.10f && cx < 0.90f && area > 0.003f;
    }
    
    // 判断是否为地面障碍物（底部在画面下方70%以下）
    public static boolean isGroundObstacle(int label, float yMax, float area) {
        return yMax > 0.70f && area > 0.015f && area < 0.15f;
    }
    
    // 高度占比>50%算近距离
    public static boolean isCloseRange(float heightRatio) {
        return heightRatio > 0.5f;
    }
}
