package com.tencent.yolov11ncnn;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MainLayoutDesignTest {

    @Test
    public void mainLayoutContainsNewProductPanels() throws IOException {
        String xml = readMainLayout();

        assertTrue(xml.contains("@+id/topStatusPanel"));
        assertTrue(xml.contains("@+id/movementStatusButton"));
        assertTrue(xml.contains("@+id/buttonSwitchCpuGpu"));
        assertTrue(xml.contains("@+id/badgeVoiceStatus"));
        assertTrue(xml.contains("@+id/buttonActiveQuery"));
        assertTrue(xml.contains("@+id/voicePromptBox"));
        assertTrue(xml.contains("@+id/buttonHoldToTalk"));
    }

    @Test
    public void cpuGpuButtonShouldBeVisibleInTopStatusPanel() throws IOException {
        String xml = readMainLayout();

        int panelStart = xml.indexOf("android:id=\"@+id/topStatusPanel\"");
        int panelEnd = xml.indexOf("</LinearLayout>", xml.indexOf("android:id=\"@+id/buttonSwitchCamera\""));
        int cpuGpuButton = xml.indexOf("android:id=\"@+id/buttonSwitchCpuGpu\"");
        int cameraButton = xml.indexOf("android:id=\"@+id/buttonSwitchCamera\"");

        assertTrue(cpuGpuButton > panelStart);
        assertTrue(cpuGpuButton < panelEnd);
        assertTrue(cpuGpuButton < cameraButton);
    }

    private String readMainLayout() throws IOException {
        Path direct = Paths.get("src", "main", "res", "layout", "main.xml");
        if (Files.exists(direct)) {
            return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
        }

        Path nested = Paths.get("app", "src", "main", "res", "layout", "main.xml");
        return new String(Files.readAllBytes(nested), StandardCharsets.UTF_8);
    }
}
