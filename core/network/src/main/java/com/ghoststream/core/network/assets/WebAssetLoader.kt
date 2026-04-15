package com.ghoststream.core.network.assets

import android.content.Context
import java.io.FileNotFoundException

class WebAssetLoader(
    private val context: Context,
) {
    fun readText(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    fun readBytes(path: String): ByteArray {
        return context.assets.open(path).use { it.readBytes() }
    }

    fun exists(path: String): Boolean {
        return runCatching {
            context.assets.open(path).close()
            true
        }.getOrElse { error ->
            if (error is FileNotFoundException) false else false
        }
    }
}

