cmake_minimum_required(VERSION 3.31.6)

# Le moteur de l'assistant : llama.cpp, à la même version que sur PC (b11541), téléchargé une fois avec son
# empreinte vérifiée, et compilé pour le téléphone (réglages repris de l'exemple Android de llama.cpp).
project("evermind-assistant" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86")
    set(GGML_CPU_KLEIDIAI OFF)
    set(GGML_OPENMP OFF)
else()
    message(FATAL_ERROR "Assistant : ABI non prise en charge (${ANDROID_ABI})")
endif()

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(llama
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11541.tar.gz
    URL_HASH SHA256=0ba976571dd4f90ae72a9b34ee48808df127d8eb56cf2200e83879b0f9832cac
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(llama)

add_library(evermind-assistant SHARED assistant_jni.cpp)

target_include_directories(evermind-assistant PRIVATE
    ${llama_SOURCE_DIR}
    ${llama_SOURCE_DIR}/common
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
    ${llama_SOURCE_DIR}/vendor)

target_link_libraries(evermind-assistant llama llama-common android log)
