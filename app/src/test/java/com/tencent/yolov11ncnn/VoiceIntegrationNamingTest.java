package com.tencent.yolov11ncnn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tencent.yolov11ncnn.repository.DetectionRepository;
import com.tencent.yolov11ncnn.viewmodel.MainViewModel;

import org.junit.Test;

import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class VoiceIntegrationNamingTest {

    @Test
    public void detectionRepositoryUsesUnifiedOfflineSpeechInitializationName() {
        assertTrue(hasMethod(
                DetectionRepository.class,
                "initializeOfflineSpeechRecognition",
                DetectionRepository.VoiceInitCallback.class
        ));
        assertEquals(1, countInitializeMethods(DetectionRepository.class));
    }

    @Test
    public void mainViewModelUsesUnifiedOfflineSpeechInitializationName() {
        assertTrue(hasMethod(
                MainViewModel.class,
                "initializeOfflineSpeechRecognition",
                DetectionRepository.VoiceInitCallback.class
        ));
        assertEquals(1, countInitializeMethods(MainViewModel.class));
    }

    @Test
    public void mainResourcesShouldNotContainXfyunAccountConfiguration() throws IOException {
        String xml = readMainStrings();

        assertTrue(xml.contains("app_name"));
        assertFalse(xml.contains("xfyun_appid"));
        assertFalse(xml.contains("xfyun_api_key"));
        assertFalse(xml.contains("xfyun_api_secret"));
    }

    private boolean hasMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        try {
            Method ignored = type.getDeclaredMethod(methodName, parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private int countInitializeMethods(Class<?> type) {
        return (int) Arrays.stream(type.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("initialize"))
                .count();
    }

    private String readMainStrings() throws IOException {
        Path direct = Paths.get("src", "main", "res", "values", "strings.xml");
        if (Files.exists(direct)) {
            return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
        }

        Path nested = Paths.get("app", "src", "main", "res", "values", "strings.xml");
        return new String(Files.readAllBytes(nested), StandardCharsets.UTF_8);
    }
}
