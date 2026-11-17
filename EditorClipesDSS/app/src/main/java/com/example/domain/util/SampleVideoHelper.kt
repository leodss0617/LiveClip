package com.example.domain.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.R
import java.io.File
import java.io.FileOutputStream

object SampleVideoHelper {

    private const val TAG = "SampleVideoHelper"
    private const val SAMPLE_FILE_NAME = "cortaja_sample_live.mp4"
    private const val MIN_VALID_SIZE_BYTES = 10_000L

    /**
     * Returns the canonical, 100% reliable MediaPlayer-compatible URI for the sample video.
     * Uses android.resource:// scheme which is natively supported without filesystem permission restrictions.
     */
    fun getOrCreateSampleVideoUri(context: Context): Uri {
        return Uri.parse("android.resource://${context.packageName}/${R.raw.sample_live}")
    }

    /**
     * Returns the sample video File, copying from raw resource if not already in filesDir.
     */
    fun getSampleVideoFile(context: Context): File {
        val targetFile = File(context.filesDir, SAMPLE_FILE_NAME)
        if (!targetFile.exists() || targetFile.length() < MIN_VALID_SIZE_BYTES) {
            try {
                context.resources.openRawResource(R.raw.sample_live).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                targetFile.setReadable(true, false)
                Log.d(TAG, "Copied sample video from R.raw.sample_live: ${targetFile.length()} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy sample_live from raw: ${e.message}", e)
            }
        }
        return targetFile
    }
}
