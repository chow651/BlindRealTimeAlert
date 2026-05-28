package com.tencent.yolov11ncnn;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ComputeModeStateSyncTest {

    @Test
    public void viewModelShouldExposeComputeModeState() throws IOException {
        String source = readSource("viewmodel", "MainViewModel.java");

        assertTrue(source.contains("MutableLiveData<Integer> computeModeState"));
        assertTrue(source.contains("LiveData<Integer> getComputeModeState()"));
        assertTrue(source.contains("repository.setComputeModeCallback(this::onComputeModeChanged);"));
        assertTrue(source.contains("private void onComputeModeChanged(int cpuGpu)"));
    }

    @Test
    public void repositoryShouldNotifyComputeModeOnlyAfterSuccessfulLoad() throws IOException {
        String source = readSource("repository", "DetectionRepository.java");

        assertTrue(source.contains("private ComputeModeCallback computeModeCallback;"));
        assertTrue(source.contains("public boolean loadModel(android.content.res.AssetManager assetManager, int modelId, int cpuGpu)"));
        assertTrue(source.contains("notifyComputeModeChanged();"));
        assertTrue(source.contains("public interface ComputeModeCallback"));
        assertTrue(source.contains("void onComputeModeChanged(int cpuGpu);"));
    }

    private String readSource(String directory, String fileName) throws IOException {
        Path direct = Paths.get("src", "main", "java", "com", "tencent", "yolov11ncnn", directory, fileName);
        if (Files.exists(direct)) {
            return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
        }

        Path nested = Paths.get("app", "src", "main", "java", "com", "tencent", "yolov11ncnn", directory, fileName);
        return new String(Files.readAllBytes(nested), StandardCharsets.UTF_8);
    }
}
