package com.tencent.yolov11ncnn.viewmodel;

import android.app.Application;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.tencent.yolov11ncnn.DetectionQueueManager;
import com.tencent.yolov11ncnn.model.AnnouncementEvent;
import com.tencent.yolov11ncnn.model.DetectionState;
import com.tencent.yolov11ncnn.model.VoiceState;
import com.tencent.yolov11ncnn.repository.DetectionRepository;

import java.util.List;

/**
 * 主界面 ViewModel
 * 持有 UI 状态，处理 UI 逻辑，不持有 View 引用
 */
public class MainViewModel extends AndroidViewModel {
    private static final String TAG = "MainViewModel";
    
    private final DetectionRepository repository;
    
    // LiveData - 暴露给 View
    private final MutableLiveData<DetectionState> detectionState = new MutableLiveData<>();
    private final MutableLiveData<Boolean> orientationValid = new MutableLiveData<>(true);
    private final MutableLiveData<AnnouncementEvent> announcementEvent = new MutableLiveData<>();
    private final MutableLiveData<VoiceState> voiceState = new MutableLiveData<>();
    private final MutableLiveData<Integer> computeModeState = new MutableLiveData<>(1);
    
    // 内部状态
    private boolean isOrientationValid = true;
    private int currentFacing = 0;
    private int currentCpuGpu = 1;
    
    public MainViewModel(@NonNull Application application) {
        super(application);
        repository = new DetectionRepository(application);
        
        // 设置 Repository 回调
        repository.setDetectionCallback(this::onDetectionResult);
        repository.setOrientationCallback(this::onOrientationChanged);
        repository.setAnnouncementCallback(this::onAnnouncement);
        repository.setComputeModeCallback(this::onComputeModeChanged);
        repository.setVoiceStateCallback(new DetectionRepository.VoiceStateCallback() {
            @Override
            public void onVoiceStateChanged(boolean isListening) {
                MainViewModel.this.onVoiceStateChanged(isListening);
            }

            @Override
            public void onVoiceAvailable(boolean available) {
                MainViewModel.this.onVoiceAvailable(available);
            }

            @Override
            public void onVoiceError(String error) {
                MainViewModel.this.onVoiceError(error);
            }

            @Override
            public void onRecognitionResult(String text, boolean isFinal) {
                MainViewModel.this.onRecognitionResult(text, isFinal);
            }
        });
        
        // 初始化语音状态
        boolean voiceAvailable = repository.isVoiceAvailable();
        voiceState.setValue(new VoiceState(false, voiceAvailable));
    }
    
    // ========== 暴露给 View 的 LiveData ==========
    
    public LiveData<DetectionState> getDetectionState() {
        return detectionState;
    }
    
    public LiveData<Boolean> getOrientationValid() {
        return orientationValid;
    }
    
    public LiveData<AnnouncementEvent> getAnnouncementEvent() {
        return announcementEvent;
    }
    
    public LiveData<VoiceState> getVoiceState() {
        return voiceState;
    }

    public LiveData<Integer> getComputeModeState() {
        return computeModeState;
    }
    
    // ========== View 调用的方法 ==========
    
    /**
     * 加载模型
     */
    public boolean loadModel(android.content.res.AssetManager assetManager, int modelId, int cpuGpu) {
        return repository.loadModel(assetManager, modelId, cpuGpu);
    }
    
    /**
     * 开始检测
     */
    public void startDetection() {
        repository.startDetection(currentFacing);
    }
    
    /**
     * 停止检测
     */
    public void stopDetection() {
        repository.stopDetection();
    }
    
    /**
     * 设置输出窗口
     */
    public void setOutputWindow(Surface surface) {
        repository.setOutputWindow(surface);
    }
    
    /**
     * 设置屏幕尺寸
     */
    public void setScreenSize(float width, float height) {
        repository.setScreenSize(width, height);
    }
    
    /**
     * 执行场景查询
     */
    public void performSceneQuery() {
        repository.performSceneQuery();
    }
    
    /**
     * 开始语音监听
     */
    public void startVoiceListening() {
        Log.d("MainViewModel", "startVoiceListening called");
        repository.startVoiceListening();
    }

    /**
     * 停止语音监听
     */
    public void stopVoiceListening() {
        Log.d("MainViewModel", "stopVoiceListening called");
        repository.stopVoiceListening();
    }
    
    /**
     * 切换摄像头
     */
    public void switchCamera() {
        currentFacing = 1 - currentFacing;
        repository.switchCamera(currentFacing);
    }
    
    /**
     * 切换 CPU/GPU
     */
    public void toggleCpuGpu(int cpuGpu, android.content.res.AssetManager assetManager) {
        if (cpuGpu != currentCpuGpu) {
            boolean ok = repository.loadModel(assetManager, 0, cpuGpu);
            if (!ok) {
                computeModeState.postValue(currentCpuGpu);
            }
        }
    }
    
    /**
     * 重置统计
     */
    public void reset() {
        repository.reset();
    }
    
    /**
     * 获取当前摄像头
     */
    public int getCurrentFacing() {
        return currentFacing;
    }
    
    /**
     * 获取当前 CPU/GPU 模式
     */
    public int getCurrentCpuGpu() {
        return currentCpuGpu;
    }
    
    // ========== Repository 回调 ==========
    
    /**
     * 检测结果回调
     */
    private void onDetectionResult(List<DetectionQueueManager.DetectionObject> objects) {
        DetectionState state = new DetectionState(
            objects,
            repository.isUserMoving(),
            isOrientationValid,
            repository.getSessionTime()
        );
        detectionState.postValue(state);
    }
    
    /**
     * 姿态变化回调
     */
    private void onOrientationChanged(boolean isValid) {
        this.isOrientationValid = isValid;
        orientationValid.postValue(isValid);
    }
    
    /**
     * 播报回调
     */
    private void onAnnouncement(String text) {
        announcementEvent.postValue(new AnnouncementEvent(text));
    }

    private void onComputeModeChanged(int cpuGpu) {
        currentCpuGpu = cpuGpu;
        computeModeState.postValue(cpuGpu);
    }

    /**
     * 语音状态变化回调
     */
    private void onVoiceStateChanged(boolean isListening) {
        boolean voiceAvailable = repository.isVoiceAvailable();
        voiceState.postValue(new VoiceState(isListening, voiceAvailable));
    }

    /**
     * 语音可用性回调
     */
    private void onVoiceAvailable(boolean available) {
        VoiceState currentState = voiceState.getValue();
        if (currentState != null) {
            voiceState.postValue(new VoiceState(currentState.isListening(), available));
        } else {
            voiceState.postValue(new VoiceState(false, available));
        }
    }

    /**
     * 语音错误回调
     */
    private void onVoiceError(String error) {
        // 显示错误提示
        announcementEvent.postValue(new AnnouncementEvent("语音识别错误: " + error));
    }

    /**
     * 语音识别结果回调
     */
    private void onRecognitionResult(String text, boolean isFinal) {
        // 可以在这里显示识别结果
        if (isFinal) {
            announcementEvent.postValue(new AnnouncementEvent("识别: " + text));
        }
    }

    // ========== 语音相关方法 ==========

    public boolean isVoiceAvailable() {
        return repository.isVoiceAvailable();
    }

    public boolean isVoiceListening() {
        return repository.isVoiceListening();
    }

    /**
     * 初始化讯飞语音识别
     */
    public void initializeOfflineSpeechRecognition(DetectionRepository.VoiceInitCallback callback) {
        repository.initializeOfflineSpeechRecognition(callback);
    }

    /**
     * 暂停推理（语音识别期间）
     */
    public void pauseInference() {
        repository.pauseInference();
    }

    /**
     * 恢复推理
     */
    public void resumeInference() {
        repository.resumeInference();
    }

    /**
     * 释放资源
     */
    public void release() {
        repository.release();
    }

    // ========== 生命周期 ==========

    @Override
    protected void onCleared() {
        super.onCleared();
        repository.release();
    }
}
