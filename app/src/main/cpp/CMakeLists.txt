cmake_minimum_required(VERSION 3.22.1)

project(phoneagentpty C)

# 纯 C，不依赖 C++ 运行时。
add_library(phoneagentpty SHARED pty.c)

# fork() 之后子进程里要访问的符号必须显式声明可见性；默认全部导出即可。
target_compile_options(phoneagentpty PRIVATE -Wall -Wextra -O2)

find_library(log-lib log)
target_link_libraries(phoneagentpty ${log-lib})

# Android 15+ 要求原生库按 16KB 页对齐。NDK r28 默认就是 16KB，
# 这里显式写出来，避免日后换用旧 NDK 时静默退化（4KB 对齐的库在
# 16KB 页设备上会加载失败）。
target_link_options(phoneagentpty PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
