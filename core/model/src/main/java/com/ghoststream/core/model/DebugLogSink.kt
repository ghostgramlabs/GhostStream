package com.ghoststream.core.model

interface DebugLogSink {
    fun log(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )
}

object NoOpDebugLogSink : DebugLogSink {
    override fun log(tag: String, message: String, throwable: Throwable?) = Unit
}
