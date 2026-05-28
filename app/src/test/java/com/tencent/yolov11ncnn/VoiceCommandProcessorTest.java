package com.tencent.yolov11ncnn;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VoiceCommandProcessorTest {
    private static class RecordingCallback implements VoiceCommandProcessor.CommandCallback {
        String lastAction = "none";

        @Override
        public void onQueryObstacles() {
            lastAction = "query";
        }

        @Override
        public void onSwitchCamera() {
            lastAction = "camera";
        }

        @Override
        public void onSwitchCpu() {
            lastAction = "cpu";
        }

        @Override
        public void onSwitchGpu() {
            lastAction = "gpu";
        }

        @Override
        public void onTakeScreenshot() {
            lastAction = "screenshot";
        }

        @Override
        public void onPauseAnnouncement() {
            lastAction = "pause";
        }

        @Override
        public void onResumeAnnouncement() {
            lastAction = "resume";
        }

        @Override
        public void onHelp() {
            lastAction = "help";
        }

        @Override
        public void onUnknownCommand(String text) {
            lastAction = "unknown";
        }
    }

    private RecordingCallback callback;
    private VoiceCommandProcessor processor;

    @Before
    public void setUp() {
        callback = new RecordingCallback();
        processor = new VoiceCommandProcessor(callback);
    }

    @Test
    public void cpuCommandShouldNotSwitchCamera() {
        processor.processCommand("切换到CPU模式");
        assertEquals("cpu", callback.lastAction);
    }

    @Test
    public void gpuCommandShouldNotSwitchCamera() {
        processor.processCommand("切换到GPU");
        assertEquals("gpu", callback.lastAction);
    }

    @Test
    public void cameraCommandShouldSwitchCamera() {
        processor.processCommand("切换摄像头");
        assertEquals("camera", callback.lastAction);
    }
}
