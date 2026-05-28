package com.tencent.yolov11ncnn;

import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景分析器 - 分析检测结果，计算区域风险，生成场景描述
 * 
 * 区域风险权重：
 * - 横向：边缘(0.5x) < 两侧(0.8x) < 中心(1.5x)
 * - 纵向：远(0.3x) < 中(0.7x) < 近(1.5x)
 */
public class SceneAnalyzer {
    private static final String TAG = "SceneAnalyzer";
    
    // 区域边界（与RiskManager保持一致）
    private static final float X_EDGE_LEFT = 0.15f;
    private static final float X_SIDE_LEFT = 0.35f;
    private static final float X_SIDE_RIGHT = 0.65f;
    private static final float X_EDGE_RIGHT = 0.85f;
    
    // 纵向区域划分（优化后）
    private static final float Y_UPPER = 0.35f;      // 上部：0.0-0.35 (天空/建筑/无用)
    private static final float Y_FAR = 0.55f;        // 中上：0.35-0.55 (远方/地平线)
    private static final float Y_MIDDLE = 0.75f;     // 中下：0.55-0.75 (中距离)
                                                      // 下部：0.75-1.0 (近距离)
    
    // 距离判断阈值
    private static final float AREA_VERY_CLOSE = 0.50f;  // 近距离面积要求：50%
    private static final float AREA_CLOSE = 0.30f;       // 中近距离面积要求：30%
    private static final float AREA_FAR = 0.08f;         // 远距离面积上限：8%
    
    // 风险阈值（基于画面遮挡比例）
    private static final float RISK_CLEAR = 0.15f;      // 空旷
    private static final float RISK_LIGHT = 0.8f;       // 有障碍
    private static final float RISK_MODERATE = 2.0f;    // 障碍较多
    private static final float RISK_HIGH = 4.0f;        // 拥挤

    // 横滚补偿配置：小于该值视为无偏转；超过上限则按上限补偿避免过校正。
    private static final float ROLL_COMPENSATION_MIN_DEG = 3f;
    private static final float ROLL_COMPENSATION_MAX_DEG = 20f;
    private static final float STATIC_QUERY_OBSTACLE_MIN_AREA_RATIO = 0.02f;
    private static final Set<Integer> STATIC_QUERY_OBSTACLE_LABELS = new HashSet<>(
            Arrays.asList(1, 2, 3, 5, 7, 10, 11, 13, 16, 56, 57, 58, 59, 60, 62, 63));
    
    // 距离等级枚举
    public enum DistanceLevel {
        VERY_CLOSE,  // 近距离：大面积+跨越多个区域
        CLOSE,       // 中近距离
        MEDIUM,      // 中距离
        FAR          // 远距离
    }
    
    // 场景分析结果
    public static class SceneReport {
        public String summary;
        public float totalRisk;
        public List<ZoneReport> zoneReports;
        public DetectionQueueManager.DetectionObject mostDangerousTarget;
        
        public SceneReport() {
            this.zoneReports = new ArrayList<>();
            this.totalRisk = 0;
        }
    }
    
    // 单个方位报告
    public static class ZoneReport {
        public RiskManager.PositionZone zone;
        public boolean hasObstacle;
        public float riskScore;
        public String description;
        public DetectionQueueManager.DetectionObject mainTarget;
        
        public ZoneReport(RiskManager.PositionZone zone) {
            this.zone = zone;
            this.hasObstacle = false;
            this.riskScore = 0;
            this.description = "";
        }
    }
    
    // 分析当前场景
    public static SceneReport analyzeScene(List<DetectionQueueManager.DetectionObject> objects,
                                           float screenWidth, float screenHeight) {
        return analyzeScene(objects, screenWidth, screenHeight, 0f);
    }

    // 带横滚补偿的场景分析
    public static SceneReport analyzeScene(List<DetectionQueueManager.DetectionObject> objects,
                                           float screenWidth,
                                           float screenHeight,
                                           float rollDegrees) {
        SceneReport report = new SceneReport();
        
        if (objects == null || objects.isEmpty()) {
            report.summary = "前方空旷，可以安全通行";
            report.totalRisk = 0;
            return report;
        }
        
        // 检测是否有大面积遮挡目标
        DetectionQueueManager.DetectionObject largestObject = null;
        float maxAreaRatio = 0;
        
        for (DetectionQueueManager.DetectionObject obj : objects) {
            float areaRatio = obj.getAreaRatio(screenWidth, screenHeight);
            if (areaRatio > maxAreaRatio) {
                maxAreaRatio = areaRatio;
                largestObject = obj;
            }
        }
        
        // 如果存在大面积遮挡（占画面50%以上），且距离等级为VERY_CLOSE，且在中心区域
        if (largestObject != null && maxAreaRatio > LARGE_OBJECT_THRESHOLD) {
            float cx = compensateCenterX(
                    largestObject.getCenterXNormalized(screenWidth),
                    largestObject.getCenterYNormalized(screenHeight),
                    rollDegrees);
            DistanceLevel distLevel = getDistanceLevel(largestObject, screenWidth, screenHeight);
            
            // 必须同时满足：大面积 + 近距离 + 中心区域
            if (distLevel == DistanceLevel.VERY_CLOSE && cx >= 0.25f && cx <= 0.75f) {
                report.totalRisk = maxAreaRatio * 10;  // 高风险
                report.mostDangerousTarget = largestObject;
                
                // 创建单一的中心区域报告
                ZoneReport centerReport = new ZoneReport(RiskManager.PositionZone.CENTER);
                centerReport.hasObstacle = true;
                centerReport.riskScore = report.totalRisk;
                centerReport.mainTarget = largestObject;
                
                if (largestObject.isPerson()) {
                    centerReport.description = "有近距离行人";
                    report.summary = "正前方近距离行人";
                } else if (largestObject.isVehicle()) {
                    String vehicleName = getVehicleName(largestObject.getLabel());
                    centerReport.description = "有近距离" + vehicleName;
                    report.summary = "正前方近距离" + vehicleName;
                } else {
                    centerReport.description = "有近距离障碍物";
                    report.summary = "正前方近距离障碍物";
                }
                
                report.zoneReports.add(centerReport);
                
                Log.i(TAG, String.format("场景查询检测到大面积遮挡: 面积=%.2f%%, 距离=%s, 类型=%s", 
                    maxAreaRatio * 100, distLevel, report.summary));
                return report;
            }
        }
        
        // 正常情况：按方位分组分析
        Map<RiskManager.PositionZone, List<DetectionQueueManager.DetectionObject>> zoneObjects = new HashMap<>();
        for (RiskManager.PositionZone zone : RiskManager.PositionZone.values()) {
            zoneObjects.put(zone, new ArrayList<>());
        }
        
        for (DetectionQueueManager.DetectionObject obj : objects) {
            float compensatedCx = compensateCenterX(
                    obj.getCenterXNormalized(screenWidth),
                    obj.getCenterYNormalized(screenHeight),
                    rollDegrees);
            RiskManager.PositionZone zone = RiskManager.getPositionZone(compensatedCx);
            zoneObjects.get(zone).add(obj);
        }
        
        // 分析各方位
        float maxRisk = 0;
        DetectionQueueManager.DetectionObject mostDangerous = null;
        
        for (RiskManager.PositionZone zone : RiskManager.PositionZone.values()) {
            List<DetectionQueueManager.DetectionObject> objs = zoneObjects.get(zone);
            ZoneReport zoneReport = analyzeZone(zone, objs, screenWidth, screenHeight, rollDegrees);
            report.zoneReports.add(zoneReport);
            report.totalRisk += zoneReport.riskScore;
            
            if (zoneReport.mainTarget != null && zoneReport.riskScore > maxRisk) {
                maxRisk = zoneReport.riskScore;
                mostDangerous = zoneReport.mainTarget;
            }
        }
        
        report.mostDangerousTarget = mostDangerous;
        report.summary = generateSummary(report.totalRisk);
        
        return report;
    }
    
    private static ZoneReport analyzeZone(RiskManager.PositionZone zone,
                                          List<DetectionQueueManager.DetectionObject> objects,
                                          float screenWidth, float screenHeight) {
        return analyzeZone(zone, objects, screenWidth, screenHeight, 0f);
    }

    private static ZoneReport analyzeZone(RiskManager.PositionZone zone,
                                          List<DetectionQueueManager.DetectionObject> objects,
                                          float screenWidth,
                                          float screenHeight,
                                          float rollDegrees) {
        ZoneReport report = new ZoneReport(zone);
        
        if (objects == null || objects.isEmpty()) return report;
        
        report.hasObstacle = true;
        float maxRisk = 0;
        DetectionQueueManager.DetectionObject mainTarget = null;
        
        for (DetectionQueueManager.DetectionObject obj : objects) {
            float risk = calculateObjectRisk(obj, screenWidth, screenHeight, rollDegrees);
            report.riskScore += risk;
            
            if (risk > maxRisk) {
                maxRisk = risk;
                mainTarget = obj;
            }
        }
        
        report.mainTarget = mainTarget;
        if (mainTarget != null) {
            report.description = generateObjectDescription(mainTarget);
        }
        
        return report;
    }
    
    // 计算单个目标风险分数
    public static float calculateObjectRisk(DetectionQueueManager.DetectionObject obj,
                                            float screenWidth, float screenHeight) {
        return calculateObjectRisk(obj, screenWidth, screenHeight, 0f);
    }

    // 带横滚补偿的风险分数
    public static float calculateObjectRisk(DetectionQueueManager.DetectionObject obj,
                                            float screenWidth,
                                            float screenHeight,
                                            float rollDegrees) {
        float baseRisk = getTypeRisk(obj.getLabel());
        float cx = compensateCenterX(
                obj.getCenterXNormalized(screenWidth),
                obj.getCenterYNormalized(screenHeight),
                rollDegrees);
        float cy = obj.getCenterYNormalized(screenHeight);
        float positionWeight = getPositionWeight(cx, cy);
        float areaFactor = getAreaFactor(obj.getAreaRatio(screenWidth, screenHeight));
        float motionFactor = getMotionFactor(obj);
        
        // 距离等级权重（基于面积+跨度）
        DistanceLevel distLevel = getDistanceLevel(obj, screenWidth, screenHeight);
        float distanceWeight = getDistanceWeight(distLevel);
        
        return baseRisk * positionWeight * areaFactor * motionFactor * distanceWeight;
    }
    
    /**
     * 判断目标距离等级（基于面积+纵向跨度）
     */
    private static DistanceLevel getDistanceLevel(DetectionQueueManager.DetectionObject obj, 
                                                   float screenWidth, float screenHeight) {
        float areaRatio = obj.getAreaRatio(screenWidth, screenHeight);
        float top = obj.getY() / screenHeight;
        float bottom = (obj.getY() + obj.getHeight()) / screenHeight;
        float height = obj.getHeight() / screenHeight;
        
        // 1. 近距离判断：面积大 AND 跨度大
        if (areaRatio >= AREA_VERY_CLOSE) {
            // 跨越中下+下部两个区域
            if (top < Y_MIDDLE && bottom > Y_MIDDLE) {
                return DistanceLevel.VERY_CLOSE;
            }
            // 占据下部大部分
            if (bottom > 0.85f && height > 0.25f) {
                return DistanceLevel.VERY_CLOSE;
            }
        }
        
        // 2. 中近距离判断
        if (areaRatio >= AREA_CLOSE) {
            // 跨越中距离+下部
            if (top < 0.65f && bottom > Y_MIDDLE) {
                return DistanceLevel.CLOSE;
            }
        }
        // 或者面积较大且在下部
        if (areaRatio >= 0.40f && bottom > Y_MIDDLE) {
            return DistanceLevel.CLOSE;
        }
        
        // 3. 远距离判断：小目标在中上部或上部
        if (areaRatio < AREA_FAR && bottom < Y_FAR) {
            return DistanceLevel.FAR;
        }
        
        // 4. 默认中距离
        return DistanceLevel.MEDIUM;
    }
    
    /**
     * 根据距离等级获取权重
     */
    private static float getDistanceWeight(DistanceLevel level) {
        switch (level) {
            case VERY_CLOSE: return 2.0f;   // 近距离
            case CLOSE: return 1.5f;        // 中近距离
            case MEDIUM: return 1.0f;       // 中距离
            case FAR: return 0.4f;          // 远距离
            default: return 1.0f;
        }
    }
    
    /**
     * 纵向位置权重（简化版，配合距离等级使用）
     */
    private static float getVerticalWeight(float cy) {
        if (cy < Y_UPPER) return 0.1f;      // 上部：极低权重，基本忽略
        if (cy < Y_FAR) return 0.4f;        // 中上：远方
        if (cy < Y_MIDDLE) return 1.0f;     // 中下：中距离
        return 1.5f;                        // 下部：近距离
    }
    
    // 位置权重（整合横向+纵向+距离等级）
    private static float getPositionWeight(float cx, float cy) {
        float horizontalWeight;
        if (cx < X_EDGE_LEFT || cx > X_EDGE_RIGHT) {
            horizontalWeight = 0.5f;
        } else if (cx < X_SIDE_LEFT || cx > X_SIDE_RIGHT) {
            horizontalWeight = 0.8f;
        } else {
            horizontalWeight = 1.5f;
        }
        
        float verticalWeight = getVerticalWeight(cy);
        
        return horizontalWeight * verticalWeight;
    }
    
    // 类型风险
    private static float getTypeRisk(int label) {
        switch (label) {
            case 2: case 5: case 7: return 1.0f;   // 汽车、公交、卡车
            case 3: return 0.8f;                    // 摩托车
            case 1: return 0.6f;                    // 自行车
            case 0: return 0.5f;                    // 人
            case 16: return 0.4f;                   // 狗
            default: return 0.3f;
        }
    }
    
    // 面积因子
    private static float getAreaFactor(float areaRatio) {
        if (areaRatio < 0.005f) return 0.3f;
        if (areaRatio < 0.01f) return 0.5f;
        if (areaRatio < 0.02f) return 0.8f;
        if (areaRatio < 0.05f) return 1.0f;
        if (areaRatio < 0.10f) return 1.3f;
        if (areaRatio < 0.20f) return 1.6f;
        return 2.0f;
    }
    
    // 运动因子
    private static float getMotionFactor(DetectionQueueManager.DetectionObject obj) {
        if (obj.isApproaching) {
            return 2.0f + Math.max(0, obj.areaChangeRate);
        }
        return 1.0f;
    }
    
    private static String generateSummary(float totalRisk) {
        if (totalRisk < RISK_CLEAR) return "前方空旷，可以安全通行";
        if (totalRisk < RISK_LIGHT) return "前方有障碍";
        if (totalRisk < RISK_MODERATE) return "前方有障碍，请注意避让";
        if (totalRisk < RISK_HIGH) return "前方障碍较多，请小心通行";
        return "前方拥挤，建议等待或绕行";
    }
    
    private static String generateObjectDescription(DetectionQueueManager.DetectionObject obj) {
        String typeName = getTypeName(obj.getLabel());
        if (obj.isApproaching) {
            return "有" + typeName + "正在接近";
        }
        return "有" + typeName;
    }
    
    private static String getTypeName(int label) {
        switch (label) {
            case 0: return "行人";
            case 1: return "自行车";
            case 2: return "汽车";
            case 3: return "摩托车";
            case 5: return "公交车";
            case 7: return "卡车";
            case 16: return "狗";
            default: return "障碍物";
        }
    }
    
    // 生成播报文案（基于方位和障碍类型，区分正前方和左右）
    public static String generateAnnouncement(SceneReport report) {
        if (report == null) return "无法分析当前场景，查询结束";
        
        // 检查各方位是否有障碍
        ZoneReport centerReport = null;
        List<ZoneReport> sideReports = new ArrayList<>();
        
        for (ZoneReport zr : report.zoneReports) {
            if (!zr.hasObstacle || zr.riskScore < 0.10f) continue;
            
            if (zr.zone == RiskManager.PositionZone.CENTER) {
                centerReport = zr;
            } else {
                sideReports.add(zr);
            }
        }
        
        StringBuilder sb = new StringBuilder();
        
        // 情况1：正前方有障碍
        if (centerReport != null && centerReport.riskScore > 0.10f) {
            sb.append("正前方").append(centerReport.description);
            
            // 如果左右也有障碍，补充说明
            sideReports.sort((a, b) -> Float.compare(b.riskScore, a.riskScore));
            int sideCount = 0;
            for (ZoneReport zr : sideReports) {
                if (sideCount >= 2) break;
                if (zr.riskScore > 0.15f) {
                    sb.append("，").append(zr.zone.getDisplayName()).append(zr.description);
                    sideCount++;
                }
            }
            
            sb.append("，查询结束");
            return sb.toString();
        }
        
        // 情况2：正前方没有障碍，但左右有
        if (!sideReports.isEmpty()) {
            sb.append("正前方空旷");
            
            sideReports.sort((a, b) -> Float.compare(b.riskScore, a.riskScore));
            int sideCount = 0;
            for (ZoneReport zr : sideReports) {
                if (sideCount >= 2) break;
                if (zr.riskScore > 0.10f) {
                    sb.append("，").append(zr.zone.getDisplayName()).append(zr.description);
                    sideCount++;
                }
            }
            
            sb.append("，查询结束");
            return sb.toString();
        }
        
        // 情况3：都没有障碍
        return "前方空旷，可以安全通行，查询结束";
    }

    /**
     * 静止状态下的主动查询文案：
     * 1) 先回答“前方是否有人”
     * 2) 再播报已识别标签（中文、去重、不带数量）
     */
    public static String generateStaticQueryAnnouncement(List<DetectionQueueManager.DetectionObject> objects,
                                                         float screenWidth,
                                                         float screenHeight) {
        boolean hasFrontPerson = false;
        Set<String> labels = new LinkedHashSet<>();
        DetectionQueueManager.DetectionObject largestObstacle = null;
        float largestObstacleAreaRatio = 0f;

        if (objects != null) {
            for (DetectionQueueManager.DetectionObject obj : objects) {
                if (obj == null) continue;

                if (obj.isPerson()) {
                    RiskManager.PositionZone zone = obj.getPositionZone(screenWidth);
                    if (zone == RiskManager.PositionZone.LEFT_FRONT
                            || zone == RiskManager.PositionZone.CENTER
                            || zone == RiskManager.PositionZone.RIGHT_FRONT) {
                        hasFrontPerson = true;
                    }
                }

                labels.add(VoiceAnnouncementManager.getClassNameCN(obj.getLabel()));

                if (!obj.isPerson() && STATIC_QUERY_OBSTACLE_LABELS.contains(obj.getLabel())) {
                    float areaRatio = obj.getAreaRatio(screenWidth, screenHeight);
                    if (areaRatio >= STATIC_QUERY_OBSTACLE_MIN_AREA_RATIO
                            && areaRatio > largestObstacleAreaRatio) {
                        largestObstacle = obj;
                        largestObstacleAreaRatio = areaRatio;
                    }
                }
            }
        }

        String personText = hasFrontPerson ? "前方有人" : "前方没有人";

        if (!hasFrontPerson) {
            if (largestObstacle != null) {
                String obstacleName = VoiceAnnouncementManager.getClassNameCN(largestObstacle.getLabel());
                return personText + "。目前识别到面积最大的障碍物是" + obstacleName + "。";
            }
            if (labels.isEmpty()) {
                return personText + "。目前没有识别到明显物体。";
            }
            return personText + "。前方无明显障碍物。";
        }

        if (labels.isEmpty()) {
            return personText + "。目前没有识别到明显物体。";
        }

        String labelsText = joinLabelsNaturally(new ArrayList<>(labels));
        return personText + "。当前识别到" + labelsText + "。";
    }

    public static String generateStaticQueryAnnouncement(List<DetectionQueueManager.DetectionObject> objects,
                                                         float screenWidth,
                                                         float screenHeight,
                                                         float rollDegrees) {
        // 静止态查询优先保证语义稳定性，不引入横滚补偿导致的“前方有人/无人”抖动翻转。
        return generateStaticQueryAnnouncement(objects, screenWidth, screenHeight);
    }

    private static String joinLabelsNaturally(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        if (labels.size() == 1) {
            return labels.get(0);
        }
        if (labels.size() == 2) {
            return labels.get(0) + "和" + labels.get(1);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i == labels.size() - 1) {
                sb.append("和").append(labels.get(i));
            } else if (i == 0) {
                sb.append(labels.get(i));
            } else {
                sb.append("、").append(labels.get(i));
            }
        }
        return sb.toString();
    }
    
    public static boolean isClear(SceneReport report) {
        return report != null && report.totalRisk < RISK_CLEAR;
    }
    
    public static boolean isDangerous(SceneReport report) {
        return report != null && report.totalRisk >= RISK_MODERATE;
    }
    
    // ==================== 实时路况分析 ====================
    
    public enum PathStatus {
        CLEAR, OBSTACLE_LEFT, OBSTACLE_RIGHT, OBSTACLE_CENTER,
        APPROACHING_VEHICLE, APPROACHING_PERSON, DENSE_CROWD,
        SUGGEST_LEFT, SUGGEST_RIGHT
    }
    
    public static class PathResult {
        public PathStatus status;
        public String announcement;
        public int personCount;
        public int vehicleCount;
        public boolean hasApproachingTarget;
        public int suggestedDirection;  // -1=左, 0=直行, 1=右
        
        public PathResult() {
            this.status = PathStatus.CLEAR;
            this.announcement = "";
        }
    }
    
    private static final float LEFT_ZONE_END = 0.35f;
    private static final float RIGHT_ZONE_START = 0.65f;
    private static final int DENSE_PERSON_THRESHOLD = 4;
    private static final float DENSE_AREA_THRESHOLD = 0.08f;
    private static final float APPROACHING_THRESHOLD = 0.30f;
    private static final float LARGE_OBJECT_THRESHOLD = 0.50f;  // 单个目标占画面50%以上视为大面积遮挡
    
    // 实时路况分析
    public static PathResult analyzePath(List<DetectionQueueManager.DetectionObject> objects,
                                         float screenWidth, float screenHeight) {
        return analyzePath(objects, screenWidth, screenHeight, 0f);
    }

    // 带横滚补偿的实时路况分析
    public static PathResult analyzePath(List<DetectionQueueManager.DetectionObject> objects,
                                         float screenWidth,
                                         float screenHeight,
                                         float rollDegrees) {
        PathResult result = new PathResult();
        
        if (objects == null || objects.isEmpty()) {
            result.status = PathStatus.CLEAR;
            return result;
        }
        
        List<DetectionQueueManager.DetectionObject> persons = new ArrayList<>();
        List<DetectionQueueManager.DetectionObject> vehicles = new ArrayList<>();
        List<DetectionQueueManager.DetectionObject> approachingTargets = new ArrayList<>();
        DetectionQueueManager.DetectionObject largestObject = null;
        float maxAreaRatio = 0;
        
        float leftScore = 0, centerScore = 0, rightScore = 0;
        
        for (DetectionQueueManager.DetectionObject obj : objects) {
            float cx = compensateCenterX(
                    obj.getCenterXNormalized(screenWidth),
                    obj.getCenterYNormalized(screenHeight),
                    rollDegrees);
            float areaRatio = obj.getAreaRatio(screenWidth, screenHeight);
            
            // 记录最大面积目标
            if (areaRatio > maxAreaRatio) {
                maxAreaRatio = areaRatio;
                largestObject = obj;
            }
            
            if (obj.isPerson()) persons.add(obj);
            else if (obj.isVehicle()) vehicles.add(obj);
            
            if (obj.isApproaching && obj.areaChangeRate > APPROACHING_THRESHOLD) {
                approachingTargets.add(obj);
            }
            
            float score = areaRatio * 10;
            if (obj.isVehicle()) score *= 1.5f;
            
            if (cx < LEFT_ZONE_END) leftScore += score;
            else if (cx > RIGHT_ZONE_START) rightScore += score;
            else centerScore += score;
        }
        
        result.personCount = persons.size();
        result.vehicleCount = vehicles.size();
        result.hasApproachingTarget = !approachingTargets.isEmpty();
        
        // 优先级0：检测大面积遮挡（单个目标占据画面过大）
        if (largestObject != null && maxAreaRatio > LARGE_OBJECT_THRESHOLD) {
            float cx = compensateCenterX(
                    largestObject.getCenterXNormalized(screenWidth),
                    largestObject.getCenterYNormalized(screenHeight),
                    rollDegrees);
            DistanceLevel distLevel = getDistanceLevel(largestObject, screenWidth, screenHeight);
            
            // 必须同时满足：大面积 + 近距离 + 中心区域
            if (distLevel == DistanceLevel.VERY_CLOSE && cx >= 0.25f && cx <= 0.75f) {
                result.status = PathStatus.OBSTACLE_CENTER;
                
                if (largestObject.isPerson()) {
                    result.announcement = "注意正前方近距离行人";
                } else if (largestObject.isVehicle()) {
                    String vehicleName = getVehicleName(largestObject.getLabel());
                    result.announcement = "注意正前方近距离" + vehicleName;
                } else {
                    result.announcement = "注意正前方近距离障碍物";
                }
                
                Log.i(TAG, String.format("检测到大面积遮挡: 面积=%.2f%%, 距离=%s, 类型=%s", 
                    maxAreaRatio * 100, distLevel, result.announcement));
                return result;
            }
        }
        
        // 优先级1：对向来车/来人
        if (!approachingTargets.isEmpty()) {
            DetectionQueueManager.DetectionObject target = approachingTargets.get(0);
            float targetCx = compensateCenterX(
                    target.getCenterXNormalized(screenWidth),
                    target.getCenterYNormalized(screenHeight),
                    rollDegrees);
            String direction = getDirectionName(targetCx);
            
            if (target.isVehicle()) {
                result.status = PathStatus.APPROACHING_VEHICLE;
                result.announcement = "注意" + direction + "对向" + getVehicleName(target.getLabel());
            } else {
                result.status = PathStatus.APPROACHING_PERSON;
                result.announcement = "注意" + direction + "行人";
            }
            return result;
        }
        
        // 优先级2：行人密集
        if (persons.size() >= DENSE_PERSON_THRESHOLD) {
            float totalPersonArea = 0;
            for (DetectionQueueManager.DetectionObject p : persons) {
                totalPersonArea += p.getAreaRatio(screenWidth, screenHeight);
            }
            if (totalPersonArea > DENSE_AREA_THRESHOLD) {
                result.status = PathStatus.DENSE_CROWD;
                result.announcement = "周围行人密集，慢速通过";
                return result;
            }
        }
        
        // 优先级3：方向建议
        float minScore = Math.min(leftScore, Math.min(centerScore, rightScore));
        float maxScore = Math.max(leftScore, Math.max(centerScore, rightScore));
        
        if (maxScore > 0.1f && maxScore - minScore > 0.05f) {
            if (centerScore > 0.1f) {
                if (leftScore < rightScore) {
                    result.status = PathStatus.SUGGEST_LEFT;
                    result.announcement = "正前方有障碍，建议靠左";
                    result.suggestedDirection = -1;
                } else {
                    result.status = PathStatus.SUGGEST_RIGHT;
                    result.announcement = "正前方有障碍，建议靠右";
                    result.suggestedDirection = 1;
                }
                return result;
            }
        }
        
        // 优先级4：单侧障碍
        if (leftScore > 0.08f && rightScore < 0.03f) {
            result.status = PathStatus.OBSTACLE_LEFT;
            result.announcement = "注意左侧障碍";
            return result;
        }
        if (rightScore > 0.08f && leftScore < 0.03f) {
            result.status = PathStatus.OBSTACLE_RIGHT;
            result.announcement = "注意右侧障碍";
            return result;
        }
        if (centerScore > 0.08f) {
            result.status = PathStatus.OBSTACLE_CENTER;
            result.announcement = "注意正前方障碍";
            return result;
        }
        
        result.status = PathStatus.CLEAR;
        return result;
    }

    // 将受横滚影响的图像点回正到“地平参考系”，用于左右方位判定。
    public static float compensateCenterX(float centerX, float centerY, float rollDegrees) {
        float normalizedX = clamp(centerX, 0f, 1f);
        float normalizedY = clamp(centerY, 0f, 1f);
        float absRoll = Math.abs(rollDegrees);
        if (absRoll < ROLL_COMPENSATION_MIN_DEG) {
            return normalizedX;
        }

        float limitedRoll = clamp(rollDegrees, -ROLL_COMPENSATION_MAX_DEG, ROLL_COMPENSATION_MAX_DEG);
        float radians = (float) Math.toRadians(limitedRoll);
        float dx = normalizedX - 0.5f;
        float dy = normalizedY - 0.5f;

        float correctedDx = dx * (float) Math.cos(radians) + dy * (float) Math.sin(radians);
        return clamp(0.5f + correctedDx, 0f, 1f);
    }
    
    private static String getDirectionName(float centerX) {
        if (centerX < LEFT_ZONE_END) return "左侧";
        if (centerX > RIGHT_ZONE_START) return "右侧";
        return "前方";
    }
    
    private static String getVehicleName(int label) {
        switch (label) {
            case 1: return "自行车";
            case 2: return "汽车";
            case 3: return "摩托车";
            case 5: return "公交车";
            case 7: return "卡车";
            default: return "车辆";
        }
    }
    
    public static boolean shouldAnnouncePath(PathResult result) {
        return result.status != PathStatus.CLEAR && 
               result.announcement != null && 
               !result.announcement.isEmpty();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
