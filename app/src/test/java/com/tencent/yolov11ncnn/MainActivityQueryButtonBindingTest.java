package com.tencent.yolov11ncnn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MainActivityQueryButtonBindingTest {

    @Test
    public void activeQueryButtonShouldNotBindVoiceTouchHandler() throws IOException {
        String source = readMainActivity();

        assertTrue(source.contains("queryButton.setOnClickListener(v -> viewModel.performSceneQuery());"));
        assertFalse(source.contains("queryButton.setOnTouchListener(this::handleQueryButtonTouch);"));
        assertTrue(source.contains("buttonHoldToTalk.setOnTouchListener(this::handleVoiceHoldTouch);"));
    }

    private String readMainActivity() throws IOException {
        Path direct = Paths.get("src", "main", "java", "com", "tencent", "yolov11ncnn", "MainActivity.java");
        if (Files.exists(direct)) {
            return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
        }

        Path nested = Paths.get("app", "src", "main", "java", "com", "tencent", "yolov11ncnn", "MainActivity.java");
        return new String(Files.readAllBytes(nested), StandardCharsets.UTF_8);
    }
}
