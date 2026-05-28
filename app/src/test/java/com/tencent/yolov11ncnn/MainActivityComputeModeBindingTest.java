package com.tencent.yolov11ncnn;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MainActivityComputeModeBindingTest {

    @Test
    public void topBarComputeModeButtonShouldToggleAndObserveState() throws IOException {
        String source = readMainActivity();

        assertTrue(source.contains("buttonSwitchCpuGpu = findViewById(R.id.buttonSwitchCpuGpu);"));
        assertTrue(source.contains("buttonSwitchCpuGpu.setOnClickListener"));
        assertTrue(source.contains("int nextCpuGpu = viewModel.getCurrentCpuGpu() == 0 ? 1 : 0;"));
        assertTrue(source.contains("viewModel.toggleCpuGpu(nextCpuGpu, getAssets())"));
        assertTrue(source.contains("viewModel.getComputeModeState().observe(this, this::updateCpuGpuControls);"));
        assertTrue(source.contains("private void updateCpuGpuControls(Integer cpuGpu)"));
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
