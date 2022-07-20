@file:JvmName("ThreadUtil")

package com.intech.camera.util

import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

internal val mWorkerExecutor by lazy { Executors.newCachedThreadPool() }

internal val mMainHandler by lazy { Handler(Looper.getMainLooper()) }

/**
 * Execute Task in WorkerThread
 */
fun async(runnable: Runnable) {
    mWorkerExecutor.execute(runnable)
}

/**
 * Execute in Main Thread
 * Note: if current thread is MainThread, execute immediately, else post to MainLooper
 */
@JvmOverloads
fun uiThread(atFront: Boolean = false, runnable: Runnable) {
    if (isMainThread()) {
        runnable.run()
    } else {
        if (atFront) {
            mMainHandler.postAtFrontOfQueue(runnable)
        } else {
            mMainHandler.post(runnable)
        }
    }
}

/**
 * If Current Thread is Main
 */
internal fun isMainThread(): Boolean =
    if (Build.VERSION.SDK_INT >= 23) Looper.getMainLooper().isCurrentThread
    else Thread.currentThread() == Looper.getMainLooper().thread