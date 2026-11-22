cmake_minimum_required(VERSION 3.22)
project(xmrig-embed C CXX ASM)

# Embedding xmrig into libxmrig-jni.so: sibling of vendor/xmrig — the vendored
# CMakeLists sees XMRIG_EMBED_JNI and builds the shared lib instead of the exe.
set(XMRIG_EMBED_JNI ON)

# Minimal feature set: CPU miner, RandomX + Argon2, no TLS/HWLOC/GPU backends.
# CACHE + FORCE: xmrig's cmake_minimum_required(3.5) resets CMP0077 to OLD, so
# its option() calls would otherwise overwrite plain variables with defaults.
set(WITH_HWLOC OFF CACHE BOOL "" FORCE)
set(WITH_CN_LITE OFF CACHE BOOL "" FORCE)
set(WITH_CN_HEAVY OFF CACHE BOOL "" FORCE)
set(WITH_CN_PICO OFF CACHE BOOL "" FORCE)
set(WITH_CN_FEMTO OFF CACHE BOOL "" FORCE)
set(WITH_RANDOMX ON CACHE BOOL "" FORCE)
set(WITH_ARGON2 ON CACHE BOOL "" FORCE)
set(WITH_KAWPOW OFF CACHE BOOL "" FORCE)
set(WITH_GHOSTRIDER OFF CACHE BOOL "" FORCE)
set(WITH_HTTP ON CACHE BOOL "" FORCE)
set(WITH_TLS OFF CACHE BOOL "" FORCE)
set(WITH_ASM ON CACHE BOOL "" FORCE)
set(WITH_MSR OFF CACHE BOOL "" FORCE)
set(WITH_OPENCL OFF CACHE BOOL "" FORCE)
set(WITH_CUDA OFF CACHE BOOL "" FORCE)
set(WITH_NVML OFF CACHE BOOL "" FORCE)
set(WITH_ADL OFF CACHE BOOL "" FORCE)
set(WITH_BENCHMARK OFF CACHE BOOL "" FORCE)
set(WITH_DMI OFF CACHE BOOL "" FORCE)
set(WITH_ENV_VARS OFF CACHE BOOL "" FORCE)
set(WITH_SECURE_JIT OFF CACHE BOOL "" FORCE)
set(WITH_EMBEDDED_CONFIG OFF CACHE BOOL "" FORCE)

add_subdirectory(vendor/libuv EXCLUDE_FROM_ALL)
add_subdirectory(vendor/xmrig EXCLUDE_FROM_ALL)
