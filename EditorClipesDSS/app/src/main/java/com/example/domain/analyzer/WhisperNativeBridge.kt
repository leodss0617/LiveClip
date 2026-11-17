package com.example.domain.analyzer

/** JNI bridge. Input audio must be mono Float32 sampled at 16 kHz. */
class WhisperNativeBridge {
    external fun initContext(modelPath: String): Long
    external fun transcribe(contextPtr: Long, audio16KhzMono: FloatArray, language: String): Array<String>?
    external fun freeContext(contextPtr: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
