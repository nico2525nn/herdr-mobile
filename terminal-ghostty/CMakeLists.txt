cmake_minimum_required(VERSION 3.22.1)
project(ghostty_android CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Vendored libghostty-vt 1.0.0 static library per ABI (MIT, built with Zig 0.17
# for the Android targets; see vendor/ and README-LIBGHOSTTY.md).
add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/vendor/${ANDROID_ABI}/libghostty-vt.a)

add_library(ghostty_android SHARED src/main/cpp/ghostty_jni.cpp)

target_include_directories(ghostty_android PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/vendor/include)
target_compile_definitions(ghostty_android PRIVATE GHOSTTY_STATIC)
target_compile_options(ghostty_android PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(ghostty_android PRIVATE -Wl,-z,max-page-size=16384)

if(ANDROID_ABI STREQUAL "x86_64")
  target_link_options(ghostty_android PRIVATE -Wl,-z,undefs)
endif()

target_link_libraries(ghostty_android ghostty-vt log android)
