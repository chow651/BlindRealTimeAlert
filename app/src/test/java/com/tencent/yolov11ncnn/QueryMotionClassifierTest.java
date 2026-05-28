package com.tencent.yolov11ncnn;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QueryMotionClassifierTest {

    @Test
    public void testPureRotationShouldNotBeMovingForQuery() {
        QueryMotionClassifier classifier = new QueryMotionClassifier(0L);

        classifier.onGyroSample(1.10f, 2000L);
        classifier.onGyroSample(1.20f, 2200L);
        classifier.onGyroSample(1.05f, 2400L);
        classifier.onGyroSample(1.15f, 2600L);

        assertFalse(classifier.isMovingForQuery(2700L));
    }

    @Test
    public void testWalkingLikeAccelPatternShouldBeMovingForQuery() {
        QueryMotionClassifier classifier = new QueryMotionClassifier(0L);

        classifier.onAccelSample(0.78f, 2000L);
        classifier.onAccelSample(0.83f, 2250L);
        classifier.onAccelSample(0.92f, 2500L);

        assertTrue(classifier.isMovingForQuery(2600L));
    }

    @Test
    public void testStopAfterTimeoutShouldReturnStatic() {
        QueryMotionClassifier classifier = new QueryMotionClassifier(0L);

        classifier.onAccelSample(0.78f, 2000L);
        classifier.onAccelSample(0.83f, 2250L);
        classifier.onAccelSample(0.92f, 2500L);

        assertTrue(classifier.isMovingForQuery(3000L));
        assertFalse(classifier.isMovingForQuery(4100L));
    }

    @Test
    public void testCombinedAccelAndGyroPatternShouldBeMovingForQuery() {
        QueryMotionClassifier classifier = new QueryMotionClassifier(0L);

        classifier.onAccelSample(0.70f, 2000L);
        classifier.onGyroSample(0.90f, 2100L);
        classifier.onAccelSample(0.72f, 2300L);
        classifier.onGyroSample(0.95f, 2400L);

        assertTrue(classifier.isMovingForQuery(2500L));
    }
}
