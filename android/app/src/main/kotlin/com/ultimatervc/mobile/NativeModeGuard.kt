package com.ultimatervc.mobile

import android.content.Context

enum class NativeActiveMode {
    AUDIO_INFERENCE,
    REALTIME_INFERENCE,
    VOICE_CHANGER,
}

class NativeModeGuardToken internal constructor(private val mode: NativeActiveMode) {
    fun release() {
        NativeModeGuard.release(mode)
    }
}

object NativeModeGuard {
    private val lock = Any()
    private var activeMode: NativeActiveMode? = null
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    private fun isInferenceProcessAlive(): Boolean {
        val context = applicationContext ?: return activeMode != null
        return RemoteInferenceClient.isInferenceProcessAlive(context)
    }

    fun tryEnter(mode: NativeActiveMode): NativeModeGuardToken? {
        synchronized(lock) {
            if (isInferenceProcessAlive()) return null
            activeMode = mode
            return NativeModeGuardToken(mode)
        }
    }

    fun busyMessage(): String {
        return "当前有别的模式正在占用处理引擎"
    }

    internal fun release(mode: NativeActiveMode) {
        synchronized(lock) {
            if (activeMode == mode) {
                activeMode = null
            }
        }
    }
}
