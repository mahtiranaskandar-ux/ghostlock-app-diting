package com.sesame.app.data

/**
 * The one unprivileged kernel call the public SDK does not expose:
 * `sched_setaffinity` on one of the app's own threads. The run-log thread
 * uses it to stay off the cores the route's stamp depends on.
 *
 * @return 0 on success, -errno otherwise.
 */
internal object NativeCpuPin {
    init {
        System.loadLibrary("dfprobe")
    }

    external fun setThreadAffinity(tid: Int, cpu: Int): Int
}
