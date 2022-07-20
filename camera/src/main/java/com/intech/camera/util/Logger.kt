package com.intech.camera.util

import android.util.Log

interface ILog {
    /**
     * Trace is very detail log with huge amount
     */
    fun trace(tag: String, msg: String?)

    fun v(tag: String, msg: String?)

    fun d(tag: String, msg: String?)

    fun i(tag: String, msg: String?)

    fun w(tag: String, msg: String?)

    fun e(tag: String, msg: String?, exp: Throwable? = null)
}

/**
 * Format the log and output
 */
class Logger private constructor(private val name: String, val enableTrace: Boolean = false): ILog {

    companion object {
        fun newInstance(name: String = "") = Logger(name)
    }

    override fun trace(tag: String, msg: String?) {
        if (enableTrace) print(Log.VERBOSE, tag, msg)
    }

    override fun v(tag: String, msg: String?) {
        print(Log.VERBOSE, tag, msg)
    }

    override fun d(tag: String, msg: String?) {
        print(Log.DEBUG, tag, msg)
    }

    override fun i(tag: String, msg: String?) {
        print(Log.INFO, tag, msg)
    }

    override fun w(tag: String, msg: String?) {
        print(Log.WARN, tag, msg)
    }

    override fun e(tag: String, msg: String?, exp: Throwable?) {
        print(Log.ERROR, tag, if (exp != null) "$msg\n${Log.getStackTraceString(exp)}" else msg)
    }

    private fun print(priority: Int, tag: String, msg: String?) {
        val content = if (name.isNotBlank()) "$name: $msg" else msg.orEmpty()
        Log.println(priority, tag, content)
    }
}