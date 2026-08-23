cmake_minimum_required(VERSION 3.20)
project(gromozeka_notifications LANGUAGES CXX)

add_library(
    gromozeka-notifications
    SHARED
    gromozeka_notifications.cpp
    third-party/wintoast/src/wintoastlib.cpp
)

target_compile_features(gromozeka-notifications PRIVATE cxx_std_17)
target_compile_definitions(
    gromozeka-notifications
    PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)
target_include_directories(
    gromozeka-notifications
    PRIVATE
    third-party/wintoast/include
)
target_link_libraries(gromozeka-notifications PRIVATE psapi)

