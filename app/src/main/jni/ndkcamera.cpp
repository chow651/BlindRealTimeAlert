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

#include "ndkcamera.h"

#include <string>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <thread>
#include <chrono>

#include <android/log.h>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include "mat.h"

// Use process-lifetime locks for camera render path to avoid member-mutex teardown races.
static std::mutex g_window_mutex;
static std::mutex g_sensor_mutex;

static void onDisconnected(void* context, ACameraDevice* device)
{
    __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "onDisconnected %p", device);
}

static void onError(void* context, ACameraDevice* device, int error)
{
    __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "onError %p %d", device, error);
}

static void onImageAvailable(void* context, AImageReader* reader)
{
    NdkCamera* camera = static_cast<NdkCamera*>(context);
    if (!camera || !camera->try_enter_callback())
    {
        AImage* stale_image = 0;
        if (AImageReader_acquireLatestImage(reader, &stale_image) == AMEDIA_OK && stale_image)
        {
            AImage_delete(stale_image);
        }
        return;
    }
    struct CallbackGuard
    {
        NdkCamera* camera;
        ~CallbackGuard()
        {
            if (camera)
            {
                camera->leave_callback();
            }
        }
    } guard{camera};

    AImage* image = 0;
    media_status_t status = AImageReader_acquireLatestImage(reader, &image);

    if (status != AMEDIA_OK)
    {
        return;
    }

    int32_t width = 0;
    int32_t height = 0;
    AImage_getWidth(image, &width);
    AImage_getHeight(image, &height);

    int32_t y_pixelStride = 0;
    int32_t u_pixelStride = 0;
    int32_t v_pixelStride = 0;
    AImage_getPlanePixelStride(image, 0, &y_pixelStride);
    AImage_getPlanePixelStride(image, 1, &u_pixelStride);
    AImage_getPlanePixelStride(image, 2, &v_pixelStride);

    int32_t y_rowStride = 0;
    int32_t u_rowStride = 0;
    int32_t v_rowStride = 0;
    AImage_getPlaneRowStride(image, 0, &y_rowStride);
    AImage_getPlaneRowStride(image, 1, &u_rowStride);
    AImage_getPlaneRowStride(image, 2, &v_rowStride);

    uint8_t* y_data = 0;
    uint8_t* u_data = 0;
    uint8_t* v_data = 0;
    int y_len = 0;
    int u_len = 0;
    int v_len = 0;
    AImage_getPlaneData(image, 0, &y_data, &y_len);
    AImage_getPlaneData(image, 1, &u_data, &u_len);
    AImage_getPlaneData(image, 2, &v_data, &v_len);

    if (u_data == v_data + 1 && v_data == y_data + width * height && y_pixelStride == 1 && u_pixelStride == 2 && v_pixelStride == 2 && y_rowStride == width && u_rowStride == width && v_rowStride == width)
    {
        camera->on_image((unsigned char*)y_data, (int)width, (int)height);
    }
    else
    {
        unsigned char* nv21 = new unsigned char[width * height + width * height / 2];
        {
            unsigned char* yptr = nv21;
            for (int y=0; y<height; y++)
            {
                const unsigned char* y_data_ptr = y_data + y_rowStride * y;
                for (int x=0; x<width; x++)
                {
                    yptr[0] = y_data_ptr[0];
                    yptr++;
                    y_data_ptr += y_pixelStride;
                }
            }

            unsigned char* uvptr = nv21 + width * height;
            for (int y=0; y<height/2; y++)
            {
                const unsigned char* v_data_ptr = v_data + v_rowStride * y;
                const unsigned char* u_data_ptr = u_data + u_rowStride * y;
                for (int x=0; x<width/2; x++)
                {
                    uvptr[0] = v_data_ptr[0];
                    uvptr[1] = u_data_ptr[0];
                    uvptr += 2;
                    v_data_ptr += v_pixelStride;
                    u_data_ptr += u_pixelStride;
                }
            }
        }
        camera->on_image((unsigned char*)nv21, (int)width, (int)height);
        delete[] nv21;
    }
    AImage_delete(image);
}

static void onSessionActive(void* context, ACameraCaptureSession *session) {}
static void onSessionReady(void* context, ACameraCaptureSession *session) {}
static void onSessionClosed(void* context, ACameraCaptureSession *session) {}
void onCaptureFailed(void* context, ACameraCaptureSession* session, ACaptureRequest* request, ACameraCaptureFailure* failure) {}
void onCaptureSequenceCompleted(void* context, ACameraCaptureSession* session, int sequenceId, int64_t frameNumber) {}
void onCaptureSequenceAborted(void* context, ACameraCaptureSession* session, int sequenceId) {}
void onCaptureCompleted(void* context, ACameraCaptureSession* session, ACaptureRequest* request, const ACameraMetadata* result) {}

NdkCamera::NdkCamera()
{
    camera_facing = 0;
    camera_orientation = 0;
    camera_manager = 0;
    camera_device = 0;
    image_reader = 0;
    image_reader_surface = 0;
    image_reader_target = 0;
    capture_request = 0;
    capture_session_output_container = 0;
    capture_session_output = 0;
    capture_session = 0;
    camera_active = false;
    accepting_callbacks = false;
    callbacks_inflight = 0;

    // 分辨率提升至 1280x720
    media_status_t image_reader_status = AImageReader_new(1280, 720, AIMAGE_FORMAT_YUV_420_888, 2, &image_reader);
    if (image_reader_status != AMEDIA_OK || !image_reader)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "AImageReader_new failed: %d", (int)image_reader_status);
        image_reader = 0;
        return;
    }

    image_reader_status = AImageReader_getWindow(image_reader, &image_reader_surface);
    if (image_reader_status != AMEDIA_OK || !image_reader_surface)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "AImageReader_getWindow failed: %d", (int)image_reader_status);
        AImageReader_delete(image_reader);
        image_reader = 0;
        image_reader_surface = 0;
        return;
    }
    ANativeWindow_acquire(image_reader_surface);
}

NdkCamera::~NdkCamera()
{
    close();
    if (image_reader) AImageReader_delete(image_reader);
    if (image_reader_surface) ANativeWindow_release(image_reader_surface);
}

int NdkCamera::open(int _camera_facing)
{
    if (camera_device || capture_session || camera_manager)
    {
        close();
    }

    camera_facing = _camera_facing;
    camera_manager = ACameraManager_create();
    if (!camera_manager || !image_reader || !image_reader_surface)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "open precondition failed camera_manager=%p image_reader=%p image_reader_surface=%p", camera_manager, image_reader, image_reader_surface);
        close();
        return -1;
    }

    std::string camera_id;
    ACameraIdList* camera_id_list = 0;
    camera_status_t camera_status = ACameraManager_getCameraIdList(camera_manager, &camera_id_list);
    if (camera_status != ACAMERA_OK || !camera_id_list)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "ACameraManager_getCameraIdList failed: %d", (int)camera_status);
        close();
        return -1;
    }

    for (int i = 0; i < camera_id_list->numCameras; ++i)
    {
        const char* id = camera_id_list->cameraIds[i];
        ACameraMetadata* camera_metadata = 0;
        camera_status = ACameraManager_getCameraCharacteristics(camera_manager, id, &camera_metadata);
        if (camera_status != ACAMERA_OK || !camera_metadata)
        {
            __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "ACameraManager_getCameraCharacteristics failed for %s status=%d", id, (int)camera_status);
            continue;
        }

        acamera_metadata_enum_android_lens_facing_t facing = ACAMERA_LENS_FACING_FRONT;
        ACameraMetadata_const_entry e = { 0 };
        ACameraMetadata_getConstEntry(camera_metadata, ACAMERA_LENS_FACING, &e);
        facing = (acamera_metadata_enum_android_lens_facing_t)e.data.u8[0];

        if (camera_facing == 0 && facing != ACAMERA_LENS_FACING_BACK) { ACameraMetadata_free(camera_metadata); continue; }
        if (camera_facing == 1 && facing != ACAMERA_LENS_FACING_FRONT) { ACameraMetadata_free(camera_metadata); continue; }

        camera_id = id;
        ACameraMetadata_getConstEntry(camera_metadata, ACAMERA_SENSOR_ORIENTATION, &e);
        camera_orientation = (int)e.data.i32[0];
        ACameraMetadata_free(camera_metadata);
        break;
    }
    ACameraManager_deleteCameraIdList(camera_id_list);

    if (camera_id.empty())
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "no camera id found for facing=%d", camera_facing);
        close();
        return -1;
    }

    ACameraDevice_StateCallbacks camera_device_state_callbacks;
    camera_device_state_callbacks.context = this;
    camera_device_state_callbacks.onDisconnected = onDisconnected;
    camera_device_state_callbacks.onError = onError;
    camera_status = ACameraManager_openCamera(camera_manager, camera_id.c_str(), &camera_device_state_callbacks, &camera_device);
    if (camera_status != ACAMERA_OK || !camera_device)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "ACameraManager_openCamera failed: %d", (int)camera_status);
        close();
        return -1;
    }

    AImageReader_ImageListener listener;
    listener.context = this;
    listener.onImageAvailable = onImageAvailable;
    media_status_t image_reader_status = AImageReader_setImageListener(image_reader, &listener);
    if (image_reader_status != AMEDIA_OK)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "AImageReader_setImageListener failed: %d", (int)image_reader_status);
        close();
        return -1;
    }

    camera_status = ACameraDevice_createCaptureRequest(camera_device, TEMPLATE_PREVIEW, &capture_request);
    if (camera_status != ACAMERA_OK || !capture_request)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "ACameraDevice_createCaptureRequest failed: %d", (int)camera_status);
        close();
        return -1;
    }

    uint8_t af_mode = ACAMERA_CONTROL_AF_MODE_CONTINUOUS_VIDEO;
    ACaptureRequest_setEntry_u8(capture_request, ACAMERA_CONTROL_AF_MODE, 1, &af_mode);

    camera_status = ACameraOutputTarget_create(image_reader_surface, &image_reader_target);
    if (camera_status != ACAMERA_OK || !image_reader_target)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "ACameraOutputTarget_create failed: %d", (int)camera_status);
        close();
        return -1;
    }
    camera_status = ACaptureRequest_addTarget(capture_request, image_reader_target);
    if (camera_status != ACAMERA_OK)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "ACaptureRequest_addTarget failed: %d", (int)camera_status);
        close();
        return -1;
    }

    ACameraCaptureSession_stateCallbacks camera_capture_session_state_callbacks;
    camera_capture_session_state_callbacks.context = this;
    camera_capture_session_state_callbacks.onActive = onSessionActive;
    camera_capture_session_state_callbacks.onReady = onSessionReady;
    camera_capture_session_state_callbacks.onClosed = onSessionClosed;

    camera_status = ACaptureSessionOutputContainer_create(&capture_session_output_container);
    if (camera_status != ACAMERA_OK || !capture_session_output_container)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "ACaptureSessionOutputContainer_create failed: %d", (int)camera_status);
        close();
        return -1;
    }
    camera_status = ACaptureSessionOutput_create(image_reader_surface, &capture_session_output);
    if (camera_status != ACAMERA_OK || !capture_session_output)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "ACaptureSessionOutput_create failed: %d", (int)camera_status);
        close();
        return -1;
    }
    camera_status = ACaptureSessionOutputContainer_add(capture_session_output_container, capture_session_output);
    if (camera_status != ACAMERA_OK)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "ACaptureSessionOutputContainer_add failed: %d", (int)camera_status);
        close();
        return -1;
    }
    camera_status = ACameraDevice_createCaptureSession(camera_device, capture_session_output_container, &camera_capture_session_state_callbacks, &capture_session);
    if (camera_status != ACAMERA_OK || !capture_session)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "ACameraDevice_createCaptureSession failed: %d", (int)camera_status);
        close();
        return -1;
    }

    ACameraCaptureSession_captureCallbacks camera_capture_session_capture_callbacks;
    camera_capture_session_capture_callbacks.context = this;
    camera_capture_session_capture_callbacks.onCaptureStarted = 0;
    camera_capture_session_capture_callbacks.onCaptureProgressed = 0;
    camera_capture_session_capture_callbacks.onCaptureCompleted = onCaptureCompleted;
    camera_capture_session_capture_callbacks.onCaptureFailed = onCaptureFailed;
    camera_capture_session_capture_callbacks.onCaptureSequenceCompleted = onCaptureSequenceCompleted;
    camera_capture_session_capture_callbacks.onCaptureSequenceAborted = onCaptureSequenceAborted;
    camera_capture_session_capture_callbacks.onCaptureBufferLost = 0;

    camera_status = ACameraCaptureSession_setRepeatingRequest(capture_session, &camera_capture_session_capture_callbacks, 1, &capture_request, nullptr);
    if (camera_status != ACAMERA_OK)
    {
        __android_log_print(ANDROID_LOG_ERROR, "NdkCamera", "ACameraCaptureSession_setRepeatingRequest failed: %d", (int)camera_status);
        close();
        return -1;
    }

    accepting_callbacks = true;
    camera_active = true;
    return 0;
}

void NdkCamera::close()
{
    camera_active = false;
    accepting_callbacks = false;

    if (image_reader)
    {
        AImageReader_ImageListener listener;
        listener.context = nullptr;
        listener.onImageAvailable = nullptr;
        AImageReader_setImageListener(image_reader, &listener);
    }

    while (callbacks_inflight.load(std::memory_order_acquire) > 0)
    {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    if (capture_session) { ACameraCaptureSession_stopRepeating(capture_session); ACameraCaptureSession_close(capture_session); capture_session = 0; }
    if (camera_device) { ACameraDevice_close(camera_device); camera_device = 0; }
    if (capture_session_output_container) { ACaptureSessionOutputContainer_free(capture_session_output_container); capture_session_output_container = 0; }
    if (capture_session_output) { ACaptureSessionOutput_free(capture_session_output); capture_session_output = 0; }
    if (capture_request) { ACaptureRequest_free(capture_request); capture_request = 0; }
    if (image_reader_target) { ACameraOutputTarget_free(image_reader_target); image_reader_target = 0; }
    if (camera_manager) { ACameraManager_delete(camera_manager); camera_manager = 0; }
}

bool NdkCamera::try_enter_callback()
{
    if (!accepting_callbacks.load(std::memory_order_acquire))
    {
        return false;
    }

    callbacks_inflight.fetch_add(1, std::memory_order_acq_rel);
    if (!accepting_callbacks.load(std::memory_order_acquire))
    {
        leave_callback();
        return false;
    }

    return true;
}

void NdkCamera::leave_callback()
{
    callbacks_inflight.fetch_sub(1, std::memory_order_acq_rel);
}

void NdkCamera::on_image(const cv::Mat& rgb) const {}

void NdkCamera::on_image(const unsigned char* nv21, int nv21_width, int nv21_height) const
{
    int w = 0, h = 0, rotate_type = 0;
    if (camera_orientation == 0) {
        w = nv21_width; h = nv21_height;
        rotate_type = camera_facing == 0 ? 1 : 2; // 后置 1 (正常), 前置 2 (镜像)
    } else if (camera_orientation == 90) {
        w = nv21_height; h = nv21_width;
        rotate_type = camera_facing == 0 ? 6 : 5; // 后置 6 (顺时针90), 前置 5 (顺时针90+镜像)
    } else if (camera_orientation == 180) {
        w = nv21_width; h = nv21_height;
        rotate_type = camera_facing == 0 ? 3 : 4; // 后置 3 (180度), 前置 4 (180度+镜像)
    } else if (camera_orientation == 270) {
        w = nv21_height; h = nv21_width;
        rotate_type = camera_facing == 0 ? 8 : 7; // 后置 8 (逆时针90), 前置 7 (逆时针90+镜像)
    }

    cv::Mat nv21_rotated(h + h / 2, w, CV_8UC1);
    ncnn::kanna_rotate_yuv420sp(nv21, nv21_width, nv21_height, nv21_rotated.data, w, h, rotate_type);
    cv::Mat rgb(h, w, CV_8UC3);
    ncnn::yuv420sp2rgb(nv21_rotated.data, w, h, rgb.data);
    on_image(rgb);
}

NdkCameraWindow::NdkCameraWindow() : NdkCamera() {
    sensor_manager = ASensorManager_getInstance();
    accelerometer_sensor = ASensorManager_getDefaultSensor(sensor_manager, ASENSOR_TYPE_ACCELEROMETER);
    sensor_event_queue = 0; win = 0; accelerometer_orientation = 0;
}
NdkCameraWindow::~NdkCameraWindow() {
    {
        std::lock_guard<std::mutex> sensor_guard(g_sensor_mutex);
        if (accelerometer_sensor && sensor_event_queue) {
            ASensorEventQueue_disableSensor(sensor_event_queue, accelerometer_sensor);
        }
        if (sensor_event_queue) {
            ASensorManager_destroyEventQueue(sensor_manager, sensor_event_queue);
            sensor_event_queue = 0;
        }
    }
    std::lock_guard<std::mutex> guard(g_window_mutex);
    if (win) {
        ANativeWindow_release(win);
        win = nullptr;
    }
}
void NdkCameraWindow::set_window(ANativeWindow* _win) {
    std::lock_guard<std::mutex> guard(g_window_mutex);

    if (_win) {
        ANativeWindow_acquire(_win);
    }

    ANativeWindow* old = win;
    win = _win;

    if (old) {
        ANativeWindow_release(old);
    }
}
void NdkCameraWindow::on_image_render(cv::Mat& rgb) const {}

void NdkCameraWindow::on_image(const unsigned char* nv21, int nv21_width, int nv21_height) const
{
    ANativeWindow* local_win = nullptr;
    {
        std::lock_guard<std::mutex> guard(g_window_mutex);
        local_win = win;
        if (local_win) {
            ANativeWindow_acquire(local_win);
        }
    }

    // 防御性检查：如果窗口未设置，直接返回
    if (!local_win) {
        __android_log_print(ANDROID_LOG_WARN, "NdkCamera", "on_image called but window is null, skipping frame");
        return;
    }

    auto release_local_win = [&]() {
        ANativeWindow_release(local_win);
    };

    if (!nv21 || nv21_width <= 0 || nv21_height <= 0) {
        release_local_win();
        return;
    }
    
    {
        std::lock_guard<std::mutex> sensor_guard(g_sensor_mutex);
        if (!sensor_event_queue) {
            sensor_event_queue = ASensorManager_createEventQueue(sensor_manager, ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS), 233, 0, 0);
            if (sensor_event_queue && accelerometer_sensor) {
                ASensorEventQueue_enableSensor(sensor_event_queue, accelerometer_sensor);
            }
        }
        if (sensor_event_queue) {
            int id = ALooper_pollAll(0, 0, 0, 0);
            if (id == 233) {
                ASensorEvent e[8];
                ssize_t n = 0;
                while (ASensorEventQueue_hasEvents(sensor_event_queue) == 1) {
                    n = ASensorEventQueue_getEvents(sensor_event_queue, e, 8);
                    if (n < 0) break;
                }
                if (n > 0) {
                    float ax = e[n - 1].acceleration.x, ay = e[n - 1].acceleration.y;
                    if (ay > 7) accelerometer_orientation = 0;
                    else if (ax < -7) accelerometer_orientation = 90;
                    else if (ay < -7) accelerometer_orientation = 180;
                    else if (ax > 7) accelerometer_orientation = 270;
                }
            }
        }
    }

    int rotate_type = 0;
    int w = 0;
    int h = 0;
    if (camera_facing == 0) {
        if (camera_orientation == 0) rotate_type = 1;
        else if (camera_orientation == 90) rotate_type = 6;
        else if (camera_orientation == 180) rotate_type = 3;
        else rotate_type = 8;
    } else {
        if (camera_orientation == 0) rotate_type = 2;
        else if (camera_orientation == 90) rotate_type = 5;
        else if (camera_orientation == 180) rotate_type = 4;
        else rotate_type = 7;
    }

    if (camera_orientation == 90 || camera_orientation == 270) {
        w = nv21_height;
        h = nv21_width;
    } else {
        w = nv21_width;
        h = nv21_height;
    }

    if (w <= 0 || h <= 0) {
        release_local_win();
        return;
    }

    cv::Mat nv21_rotated(h + h / 2, w, CV_8UC1);
    ncnn::kanna_rotate_yuv420sp(nv21, nv21_width, nv21_height, nv21_rotated.data, w, h, rotate_type);

    cv::Mat rgb(h, w, CV_8UC3);
    ncnn::yuv420sp2rgb(nv21_rotated.data, w, h, rgb.data);
    on_image_render(rgb);

    cv::Mat display = rgb;
    if (accelerometer_orientation == 90) {
        cv::rotate(rgb, display, cv::ROTATE_90_CLOCKWISE);
    } else if (accelerometer_orientation == 180) {
        cv::rotate(rgb, display, cv::ROTATE_180);
    } else if (accelerometer_orientation == 270) {
        cv::rotate(rgb, display, cv::ROTATE_90_COUNTERCLOCKWISE);
    }

    int window_w = ANativeWindow_getWidth(local_win);
    int window_h = ANativeWindow_getHeight(local_win);
    if (window_w <= 0 || window_h <= 0) {
        release_local_win();
        return;
    }

    int buffer_w = window_w;
    int buffer_h = window_h;

    const float fit_scale = std::min((float)buffer_w / (float)display.cols, (float)buffer_h / (float)display.rows);
    int draw_w = std::max(1, (int)(display.cols * fit_scale));
    int draw_h = std::max(1, (int)(display.rows * fit_scale));

    cv::Mat rgb_draw;
    if (draw_w != display.cols || draw_h != display.rows) {
        const int interp = fit_scale < 1.f ? cv::INTER_AREA : cv::INTER_LINEAR;
        cv::resize(display, rgb_draw, cv::Size(draw_w, draw_h), 0.f, 0.f, interp);
    } else {
        rgb_draw = display;
    }

    static int s_buffer_w = 0;
    static int s_buffer_h = 0;
    if (s_buffer_w != buffer_w || s_buffer_h != buffer_h) {
        __android_log_print(ANDROID_LOG_INFO, "ndkcamera",
                "preview resize window=%dx%d buffer=%dx%d draw=%dx%d display=%dx%d",
                window_w, window_h, buffer_w, buffer_h, draw_w, draw_h, display.cols, display.rows);
        int ret = ANativeWindow_setBuffersGeometry(local_win, buffer_w, buffer_h, WINDOW_FORMAT_RGBA_8888);
        if (ret == 0) {
            s_buffer_w = buffer_w;
            s_buffer_h = buffer_h;
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "ndkcamera", "ANativeWindow_setBuffersGeometry failed: %d", ret);
            release_local_win();
            return;
        }
    }

    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(local_win, &buf, NULL) == 0) {
        const int copy_h = std::min((int)buf.height, rgb_draw.rows);
        const int copy_w = std::min((int)buf.width, rgb_draw.cols);
        const int offset_x = std::max(0, ((int)buf.width - copy_w) / 2);
        const int offset_y = std::max(0, ((int)buf.height - copy_h) / 2);

        auto clear_rows = [&](int y_begin, int y_end) {
            y_begin = std::max(0, y_begin);
            y_end = std::min((int)buf.height, y_end);
            if (y_begin >= y_end) return;
            for (int y = y_begin; y < y_end; y++) {
                unsigned char* outptr = (unsigned char*)buf.bits + buf.stride * 4 * y;
                std::memset(outptr, 0, (size_t)buf.stride * 4);
            }
        };

        // 仅清理黑边区域，避免每帧整屏清零带来的内存带宽开销。
        if (offset_y > 0) {
            clear_rows(0, offset_y);
        }
        if (offset_y + copy_h < (int)buf.height) {
            clear_rows(offset_y + copy_h, (int)buf.height);
        }

        if (copy_w > 0 && copy_h > 0) {
            cv::Mat rgb_roi = rgb_draw(cv::Rect(0, 0, copy_w, copy_h));
            cv::Mat rgba_draw;
            cv::cvtColor(rgb_roi, rgba_draw, cv::COLOR_RGB2RGBA);

            for (int y = 0; y < copy_h; y++) {
                unsigned char* row_ptr = (unsigned char*)buf.bits + buf.stride * 4 * (y + offset_y);
                if (offset_x > 0) {
                    std::memset(row_ptr, 0, (size_t)offset_x * 4);
                }

                std::memcpy(row_ptr + offset_x * 4, rgba_draw.ptr<const unsigned char>(y), (size_t)copy_w * 4);

                const int right_clear_px = (int)buf.width - offset_x - copy_w;
                if (right_clear_px > 0) {
                    std::memset(row_ptr + (offset_x + copy_w) * 4, 0, (size_t)right_clear_px * 4);
                }
            }
        }
        ANativeWindow_unlockAndPost(local_win);
    }

    release_local_win();
}
