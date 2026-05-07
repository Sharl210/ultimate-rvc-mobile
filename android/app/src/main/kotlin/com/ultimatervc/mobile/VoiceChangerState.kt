package com.ultimatervc.mobile

enum class VoiceChangerOverlayState {
    IDLE,
    RECORDING,
    PROCESSING,
    PROCESSING_PAUSED,
    PROCESSING_FAILED,
    READY,
    PLAYING,
    PAUSED,
    TRIAL_PLAYING,
    TRIAL_PAUSED;

    fun transitionOnTap(): VoiceChangerOverlayState {
        return when (this) {
            IDLE -> RECORDING
            RECORDING -> PROCESSING
            PROCESSING -> PROCESSING
            PROCESSING_PAUSED -> PROCESSING
            PROCESSING_FAILED -> PROCESSING
            READY -> TRIAL_PLAYING
            PLAYING -> PAUSED
            PAUSED -> PLAYING
            TRIAL_PLAYING -> TRIAL_PAUSED
            TRIAL_PAUSED -> TRIAL_PLAYING
        }
    }

    fun transitionOnLongPress(): VoiceChangerOverlayState {
        return when (this) {
            PROCESSING -> IDLE
            PROCESSING_PAUSED -> IDLE
            PROCESSING_FAILED -> IDLE
            READY -> IDLE
            PLAYING -> IDLE
            PAUSED -> IDLE
            TRIAL_PLAYING -> READY
            TRIAL_PAUSED -> READY
            IDLE -> IDLE
            RECORDING -> IDLE
        }
    }
}
