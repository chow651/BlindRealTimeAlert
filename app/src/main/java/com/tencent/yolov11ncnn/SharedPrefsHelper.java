package com.tencent.yolov11ncnn;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences 工具类
 * 用于管理应用的首次启动、权限授予、教程完成等状态
 */
public class SharedPrefsHelper {
    private static final String PREFS_NAME = "voice_guide_prefs";
    private static final String KEY_FIRST_LAUNCH = "is_first_launch";
    private static final String KEY_PERMISSIONS_GRANTED = "permissions_granted";
    private static final String KEY_TUTORIAL_COMPLETED = "tutorial_completed";
    
    /**
     * 检查是否首次启动
     */
    public static boolean isFirstLaunch(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true);
    }
    
    /**
     * 标记首次启动完成
     */
    public static void setFirstLaunchCompleted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();
    }
    
    /**
     * 检查权限是否已授予
     */
    public static boolean arePermissionsGranted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PERMISSIONS_GRANTED, false);
    }
    
    /**
     * 标记权限已授予
     */
    public static void setPermissionsGranted(Context context, boolean granted) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_PERMISSIONS_GRANTED, granted).apply();
    }
    
    /**
     * 检查教程是否已完成
     */
    public static boolean isTutorialCompleted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_TUTORIAL_COMPLETED, false);
    }
    
    /**
     * 标记教程已完成
     */
    public static void setTutorialCompleted(Context context, boolean completed) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_TUTORIAL_COMPLETED, completed).apply();
    }
    
    /**
     * 重置所有设置（用于测试）
     */
    public static void resetAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }
}
