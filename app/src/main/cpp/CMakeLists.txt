cmake_minimum_required(VERSION 3.22.1)
project("opendroid_pty")

add_library(opendroid_pty SHARED pty_jni.c)

find_library(log-lib log)
target_link_libraries(opendroid_pty ${log-lib})
