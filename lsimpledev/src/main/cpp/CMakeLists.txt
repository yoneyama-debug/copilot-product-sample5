# Sets the minimum version of CMake required to build the native
# library. You should either keep the default value or only pass a
# value of 3.4.0 or lower.

cmake_minimum_required(VERSION 3.4.1)
project("tssciv2dev")

# 依存ソースのベースパス
file(TO_CMAKE_PATH "${SSTAUDIO_PROJ}" SSTAUDIO_PROJ)
file(TO_CMAKE_PATH "${SSTAUDIO_LIBS}" SSTAUDIO_LIBS)

# ソース列挙
set(src
        FileRecorder.cpp
        RawFloatRecorder.cpp
        )

# 依存.soの指定
link_directories( ${SSTAUDIO_LIBS}/${ANDROID_ABI} )

add_library(tssciv2dev SHARED ${src} )

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++14")

# インクルードパスの指定
target_include_directories(
        tssciv2dev
        PRIVATE
        ${SSTAUDIO_PROJ}/../concurrentqueue
        ${SSTAUDIO_PROJ}/src/main/cpp/
        ${CMAKE_SOURCE_DIR}/../../../../TSSCIV2/src/main/cpp/
)

find_library(log-lib log)

target_link_libraries(
        tssciv2dev
        ${log-lib}
        sstaudio
        sstcommon
)
