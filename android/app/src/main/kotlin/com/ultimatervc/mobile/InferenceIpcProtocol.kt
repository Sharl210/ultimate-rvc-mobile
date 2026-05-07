package com.ultimatervc.mobile

object InferenceIpcProtocol {
    const val MSG_INFER_AUDIO = 1
    const val MSG_CANCEL = 2
    const val MSG_PROGRESS = 3
    const val MSG_SUCCESS = 4
    const val MSG_ERROR = 5
    const val MSG_START_REALTIME = 6
    const val MSG_STOP_REALTIME = 7

    const val KEY_MODELS_DIR = "models_dir"
    const val KEY_SONG_PATH = "song_path"
    const val KEY_MODEL_PATH = "model_path"
    const val KEY_INDEX_PATH = "index_path"
    const val KEY_PITCH_CHANGE = "pitch_change"
    const val KEY_INDEX_RATE = "index_rate"
    const val KEY_FORMANT = "formant"
    const val KEY_FILTER_RADIUS = "filter_radius"
    const val KEY_RMS_MIX_RATE = "rms_mix_rate"
    const val KEY_PROTECT_RATE = "protect_rate"
    const val KEY_SAMPLE_RATE = "sample_rate"
    const val KEY_NOISE_GATE_DB = "noise_gate_db"
    const val KEY_OUTPUT_DENOISE_ENABLED = "output_denoise_enabled"
    const val KEY_VOCAL_RANGE_FILTER_ENABLED = "vocal_range_filter_enabled"
    const val KEY_PARALLEL_CHUNK_COUNT = "parallel_chunk_count"
    const val KEY_ALLOW_RESUME = "allow_resume"
    const val KEY_WORKSPACE_RELATIVE_PATH = "workspace_relative_path"
    const val KEY_EXTRA_INFERENCE_LENGTH = "extra_inference_length"
    const val KEY_DELAY_BUFFER_SECONDS = "delay_buffer_seconds"
    const val KEY_SAMPLE_LENGTH = "sample_length"
    const val KEY_CROSSFADE_LENGTH = "crossfade_length"
    const val KEY_PERCENT = "percent"
    const val KEY_STEP = "step"
    const val KEY_OUTPUT_PATH = "output_path"
    const val KEY_ERROR_CODE = "error_code"
    const val KEY_ERROR_MESSAGE = "error_message"
}
