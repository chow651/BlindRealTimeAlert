// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>

#include <android/log.h>

#include <jni.h>

#include <string>
#include <vector>
#include <mutex>

#include <platform.h>
#include <benchmark.h>

#include "yolov11.h"

#include "ndkcamera.h"

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#if __ARM_NEON
#include <arm_neon.h>
#endif // __ARM_NEON

static int draw_unsupported(cv::Mat& rgb)
{
    const char text[] = "unsupported";

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 1.0, 1, &baseLine);

    int y = (rgb.rows - label_size.height) / 2;
    int x = (rgb.cols - label_size.width) / 2;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 1.0, cv::Scalar(0, 0, 0));

    return 0;
}

static int draw_fps(cv::Mat& rgb)
{
    // resolve moving average
    float avg_fps = 0.f;
    {
        static double t0 = 0.f;
        static float fps_history[10] = {0.f};

        double t1 = ncnn::get_current_time();
        if (t0 == 0.f)
        {
            t0 = t1;
            return 0;
        }

        float fps = 1000.f / (t1 - t0);
        t0 = t1;

        for (int i = 9; i >= 1; i--)
        {
            fps_history[i] = fps_history[i - 1];
        }
        fps_history[0] = fps;

        if (fps_history[9] == 0.f)
        {
            return 0;
        }

        for (int i = 0; i < 10; i++)
        {
            avg_fps += fps_history[i];
        }
        avg_fps /= 10.f;
    }

    char text[32];
    sprintf(text, "FPS=%.2f", avg_fps);

    int baseLine = 0;
    cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 0.5, 1, &baseLine);

    int y = 0;
    int x = rgb.cols - label_size.width;

    cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                    cv::Scalar(255, 255, 255), -1);

    cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                cv::FONT_HERSHEY_SIMPLEX, 0.5, cv::Scalar(0, 0, 0));

    return 0;
}

//static Inference_det* g_yolo = 0;
static Inference* g_yolo = 0;
static ncnn::Mutex lock;

// Java回调相关
static JavaVM* g_jvm = 0;
static jobject g_detection_callback = 0;
static jmethodID g_onDetectionResult_methodID = 0;

class MyNdkCamera : public NdkCameraWindow
{
public:
    virtual void on_image_render(cv::Mat& rgb) const;
};

void MyNdkCamera::on_image_render(cv::Mat& rgb) const
{
    // nanodet
    {
        ncnn::MutexLockGuard g(lock);

        if (g_yolo)
        {
            std::vector<Object> objects;
            objects = g_yolo->runInference(rgb);

            g_yolo->draw(rgb, objects);
            
            // 通过JNI回调将检测结果传递给Java层
            if (g_detection_callback && g_jvm && g_onDetectionResult_methodID)
            {
                JNIEnv* env = 0;
                int getEnvStat = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_4);
                bool attached = false;
                
                if (getEnvStat == JNI_EDETACHED) {
                    // 如果当前线程未附加到JVM，则附加它
                    if (g_jvm->AttachCurrentThread(&env, NULL) == 0) {
                        attached = true;
                    } else {
                        __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Failed to attach thread");
                    }
                } else if (getEnvStat == JNI_OK) {
                    // 线程已经附加
                } else if (getEnvStat == JNI_EVERSION) {
                    __android_log_print(ANDROID_LOG_ERROR, "ncnn", "JNI version not supported");
                }
                
                if (env)
                {
                    int count = objects.size();
                    if (count > 0)
                    {
                        jintArray labels = env->NewIntArray(count);
                        jfloatArray probs = env->NewFloatArray(count);
                        jfloatArray rects = env->NewFloatArray(count * 4);  // x, y, width, height
                        
                        if (labels && probs && rects)
                        {
                            std::vector<jint> labels_buf(count);
                            std::vector<jfloat> probs_buf(count);
                            std::vector<jfloat> rects_buf(count * 4);

                            for (int i = 0; i < count; i++)
                            {
                                labels_buf[i] = objects[i].label;
                                probs_buf[i] = objects[i].prob;
                                rects_buf[i * 4 + 0] = objects[i].rect.x;
                                rects_buf[i * 4 + 1] = objects[i].rect.y;
                                rects_buf[i * 4 + 2] = objects[i].rect.width;
                                rects_buf[i * 4 + 3] = objects[i].rect.height;
                            }

                            env->SetIntArrayRegion(labels, 0, count, labels_buf.data());
                            env->SetFloatArrayRegion(probs, 0, count, probs_buf.data());
                            env->SetFloatArrayRegion(rects, 0, count * 4, rects_buf.data());

                            if (!env->ExceptionCheck())
                            {
                                env->CallVoidMethod(g_detection_callback, g_onDetectionResult_methodID,
                                                  labels, probs, rects, count);
                            }

                            // 检查Java异常，防止后续JNI调用崩溃
                            if (env->ExceptionCheck())
                            {
                                env->ExceptionDescribe();
                                env->ExceptionClear();
                            }

                            env->DeleteLocalRef(labels);
                            env->DeleteLocalRef(probs);
                            env->DeleteLocalRef(rects);
                        }
                    }
                    
                    if (attached) {
                        g_jvm->DetachCurrentThread();
                    }
                }
            }
        }
        /*if (g_yolo)
        {
            std::vector<Detection> objects;
            objects = g_yolo->runInference(rgb);

            g_yolo->draw(rgb, objects);
        }*/
        else
        {
            draw_unsupported(rgb);
        }
    }

    draw_fps(rgb);
}

static MyNdkCamera* g_camera = 0;
static std::mutex g_camera_mutex;

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI_OnLoad");

    g_jvm = vm;
    {
        std::lock_guard<std::mutex> camera_guard(g_camera_mutex);
        g_camera = new MyNdkCamera;
    }

    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "JNI_OnUnload");

    {
        ncnn::MutexLockGuard g(lock);

        delete g_yolo;
        g_yolo = 0;
    }

    // 清理Java回调引用
    if (g_detection_callback && g_jvm)
    {
        JNIEnv* env = 0;
        if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_4) == JNI_OK)
        {
            env->DeleteGlobalRef(g_detection_callback);
            g_detection_callback = 0;
            g_onDetectionResult_methodID = 0;
        }
    }

    MyNdkCamera* camera_to_delete = 0;
    {
        std::lock_guard<std::mutex> camera_guard(g_camera_mutex);
        camera_to_delete = g_camera;
        g_camera = 0;
    }
    delete camera_to_delete;
    
    g_jvm = 0;
}

// public native boolean loadModel(AssetManager mgr, int modelid, int cpugpu);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolov11ncnn_Yolov11Ncnn_loadModel(JNIEnv* env, jobject thiz, jobject assetManager, jint modelid, jint cpugpu)
{
    if (modelid < 0 || modelid > 1 || cpugpu < 0 || cpugpu > 1)
    {
        return JNI_FALSE;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "loadModel %p", mgr);

    const char* modeltypes[] =
    {
        "n",
        "s",
    };

    const int target_sizes[] =
    {
        640,
        640,
    };

    const float mean_vals[][3] =
    {
        {0.0f, 0.0f, 0.0f},
        {0.0f, 0.0f, 0.0f},
    };

    const float norm_vals[][3] =
    {
        { 1 / 255.f, 1 / 255.f, 1 / 255.f },
        { 1 / 255.f, 1 / 255.f, 1 / 255.f },
    };

    const char* modeltype = modeltypes[(int)modelid];
    int target_size = target_sizes[(int)modelid];
    bool use_gpu = (int)cpugpu == 1;

    // reload
    {
        ncnn::MutexLockGuard g(lock);

        if (use_gpu && ncnn::get_gpu_count() == 0)
        {
            // no gpu
            delete g_yolo;
            g_yolo = 0;
        }
        else
        {
            if (!g_yolo)
                g_yolo = new Inference;
            int load_ret = g_yolo->loadNcnnNetwork(mgr, modeltype, target_size, mean_vals[(int)modelid], norm_vals[(int)modelid], use_gpu);
            if (load_ret != 0)
            {
                __android_log_print(ANDROID_LOG_ERROR, "ncnn", "loadModel failed modeltype=%s cpugpu=%d ret=%d", modeltype, cpugpu, load_ret);
                delete g_yolo;
                g_yolo = 0;
                return JNI_FALSE;
            }
        }
    }

    return JNI_TRUE;
}

// public native boolean openCamera(int facing);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolov11ncnn_Yolov11Ncnn_openCamera(JNIEnv* env, jobject thiz, jint facing)
{
    if (facing < 0 || facing > 1)
        return JNI_FALSE;

    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "openCamera %d", facing);

    std::lock_guard<std::mutex> camera_guard(g_camera_mutex);
    if (!g_camera)
        return JNI_FALSE;

    if (g_camera->open((int)facing) != 0)
        return JNI_FALSE;

    return JNI_TRUE;
}

// public native boolean closeCamera();
JNIEXPORT jboolean JNICALL Java_com_tencent_yolov11ncnn_Yolov11Ncnn_closeCamera(JNIEnv* env, jobject thiz)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "closeCamera");

    std::lock_guard<std::mutex> camera_guard(g_camera_mutex);
    if (!g_camera)
        return JNI_FALSE;

    g_camera->close();

    return JNI_TRUE;
}

// public native boolean setOutputWindow(Surface surface);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolov11ncnn_Yolov11Ncnn_setOutputWindow(JNIEnv* env, jobject thiz, jobject surface)
{
    std::lock_guard<std::mutex> camera_guard(g_camera_mutex);
    if (!g_camera)
        return JNI_FALSE;

    if (surface == nullptr)
    {
        __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "setOutputWindow null");
        g_camera->set_window(nullptr);
        return JNI_TRUE;
    }

    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "setOutputWindow %p", win);

    if (!win)
    {
        g_camera->set_window(nullptr);
        return JNI_FALSE;
    }

    g_camera->set_window(win);
    ANativeWindow_release(win);

    return JNI_TRUE;
}

// public native void setDetectionCallback(DetectionCallback callback);
JNIEXPORT void JNICALL Java_com_tencent_yolov11ncnn_Yolov11Ncnn_setDetectionCallback(JNIEnv* env, jobject thiz, jobject callback)
{
    __android_log_print(ANDROID_LOG_DEBUG, "ncnn", "setDetectionCallback");

    ncnn::MutexLockGuard g(lock);

    // 释放旧的callback引用
    if (g_detection_callback)
    {
        env->DeleteGlobalRef(g_detection_callback);
        g_detection_callback = 0;
        g_onDetectionResult_methodID = 0;
    }

    // 获取新的callback引用
    if (callback)
    {
        g_detection_callback = env->NewGlobalRef(callback);

        // 获取方法ID
        jclass callbackClass = env->GetObjectClass(callback);
        g_onDetectionResult_methodID = env->GetMethodID(callbackClass,
            "onDetectionResult", "([I[F[FI)V");

        if (!g_onDetectionResult_methodID)
        {
            __android_log_print(ANDROID_LOG_ERROR, "ncnn", "Failed to find onDetectionResult method");
        }

        env->DeleteLocalRef(callbackClass);
    }
}

}
