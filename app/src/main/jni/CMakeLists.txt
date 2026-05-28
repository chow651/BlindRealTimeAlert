project(yolov11ncnn)

cmake_minimum_required(VERSION 3.10)

# opencv4 requires c++11
set(CMAKE_CXX_STANDARD 11)

# 1. 修改这里：匹配你刚才下载的 OpenCV 4.10.0 文件夹名
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile-4.10.0-android/sdk/native/jni)
find_package(OpenCV REQUIRED core imgproc)

# 2. 修改这里：匹配你刚才下载的 ncnn 20250916 文件夹名
# 注意：${ANDROID_ABI} 不要删，它是用来自动识别手机CPU架构的
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20250916-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(yolov11ncnn SHARED yolov11ncnn.cpp ndkcamera.cpp yolov11.cpp) 

target_link_libraries(yolov11ncnn ncnn ${OpenCV_LIBS} camera2ndk mediandk)
