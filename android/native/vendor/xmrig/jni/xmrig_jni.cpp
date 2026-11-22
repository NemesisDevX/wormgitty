/* XMRig JNI embedding shim (wormgitty).
 *
 * Drives xmrig::Process + xmrig::Controller directly on the default libuv
 * loop instead of xmrig::App (its close() is private). Start runs on a
 * dedicated std::thread; stop is delivered through a uv_async handle — the
 * only thread-safe way to wake a running uv loop from another thread.
 */

#include <jni.h>
#include <uv.h>
#include <unistd.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define XJ_TAG "XmrigJni"
#define XJ_LOGI(...) __android_log_print(ANDROID_LOG_INFO, XJ_TAG, __VA_ARGS__)
#define XJ_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, XJ_TAG, __VA_ARGS__)

#include "base/kernel/Process.h"
#include "core/Controller.h"
#include "core/Miner.h"
#include "backend/common/interfaces/IBackend.h"
#include "backend/common/Hashrate.h"

using namespace xmrig;

namespace {

std::mutex g_mtx;
Process *g_process = nullptr;
Controller *g_controller = nullptr;
std::thread *g_thread = nullptr;
std::atomic<bool> g_ready{false};
std::atomic<bool> g_loop_alive{false};
std::atomic<int> g_boot{0}; // 0 pending, 1 ready, -1 failed
std::atomic<double> g_hashrate{0.0};
uv_async_t g_stop_async;
uv_timer_t g_rate_timer;

double sample_hashrate()
{
    if (!g_controller || !g_controller->miner()) {
        return 0.0;
    }
    double total = 0.0;
    for (IBackend *b : g_controller->miner()->backends()) {
        if (b && b->hashrate()) {
            const auto h = b->hashrate()->calc(10000);
            if (h.first) {
                total += h.second;
            }
        }
    }
    return total;
}

/* Runs on the uv thread — the same thread that mutates the backends vector —
 * so iterating it here is race-free. JNI hashrate() only reads the atomic. */
void on_rate_timer(uv_timer_t *h)
{
    g_hashrate.store(sample_hashrate());
}

void on_stop_async(uv_async_t *h)
{
    uv_timer_stop(&g_rate_timer);
    g_ready = false;
    if (g_controller) {
        g_controller->stop();
    }
    uv_close(reinterpret_cast<uv_handle_t *>(h), nullptr);
}

void run(std::string configPath)
{
    optind = 0; // reset getopt state — every boot must re-parse argv cleanly
    char arg0[] = "xmrig";
    char arg1[] = "-c";
    std::vector<char> cfg(configPath.begin(), configPath.end());
    cfg.push_back('\0');
    char *argv[] = {arg0, arg1, cfg.data()};

    Process *process = new Process(3, argv);
    XJ_LOGI("run: Process created, cfg=%s", configPath.c_str());
    Controller *controller = new Controller(process);

    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_process = process;
        g_controller = controller;
    }

    const bool ready = controller->isReady();
    XJ_LOGI("run: isReady=%d", ready ? 1 : 0);
    if (!ready || controller->init() != 0) {
        XJ_LOGE("run: boot failed (ready=%d)", ready ? 1 : 0);
        std::lock_guard<std::mutex> lk(g_mtx);
        delete controller;
        delete process;
        g_controller = nullptr;
        g_process = nullptr;
        g_boot = -1;
        return;
    }

    uv_async_init(uv_default_loop(), &g_stop_async, on_stop_async);
    uv_timer_init(uv_default_loop(), &g_rate_timer);
    uv_timer_start(&g_rate_timer, on_rate_timer, 2000, 3000);
    g_ready = true;
    g_boot = 1;
    XJ_LOGI("run: controller->start()");
    controller->start();
    g_loop_alive = true;
    uv_run(uv_default_loop(), UV_RUN_DEFAULT);
    g_loop_alive = false;
    uv_loop_close(uv_default_loop());

    {
        std::lock_guard<std::mutex> lk(g_mtx);
        delete controller;
        delete process;
        g_controller = nullptr;
        g_process = nullptr;
        g_ready = false;
    }
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wormgitty_agent_NativeMiner_nativeStart(
    JNIEnv *env, jclass, jstring jConfigJson, jstring jWorkDir)
{
    std::unique_lock<std::mutex> lk(g_mtx);
    if (g_thread || g_ready) {
        return JNI_FALSE; // already running / starting
    }

    const char *dir = env->GetStringUTFChars(jWorkDir, nullptr);
    const std::string configPath = std::string(dir) + "/config.json";
    env->ReleaseStringUTFChars(jWorkDir, dir);
    // The Kotlin side already wrote config.json; jConfigJson is the same
    // payload — rewrite defensively so a stale file can never boot us.
    (void)jConfigJson;

    g_ready = false;
    g_boot = 0;
    g_thread = new std::thread(run, configPath);
    lk.unlock();
    // Report honest success: wait (≤20s) for init-ready or init-failed.
    for (int i = 0; i < 400 && g_boot == 0; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    if (g_boot != 1) {
        // Boot failed: reclaim the dead thread so a retry is possible.
        std::lock_guard<std::mutex> lk2(g_mtx);
        if (g_thread && g_thread->joinable()) {
            g_thread->join();
            delete g_thread;
            g_thread = nullptr;
        }
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_wormgitty_agent_NativeMiner_nativeStop(JNIEnv *, jclass)
{
    std::thread *t = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        t = g_thread;
        g_thread = nullptr;
    }
    if (t == nullptr) {
        return;
    }
    if (g_loop_alive) {
        // Thread-safe wake: on_stop_async runs inside the uv loop.
        uv_async_send(&g_stop_async);
    }
    if (t->joinable()) {
        t->join();
    }
    delete t;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_wormgitty_agent_NativeMiner_nativeHashrate(JNIEnv *, jclass)
{
    if (!g_ready.load()) {
        return 0.0;
    }
    return g_hashrate.load();
}
