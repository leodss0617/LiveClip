package com.example.domain.analyzer

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

object MediaSourceHelper {

    private const val TAG = "MediaSourceHelper"

    /**
     * Checks if the given URI points to an accessible local media stream or file
     * before attempting to initialize native NuMediaExtractor.
     */
    fun isReadableMediaSource(context: Context, uri: Uri): Boolean {
        return try {
            when (uri.scheme) {
                "content" -> {
                    // Verify if ContentResolver can open an AssetFileDescriptor or ParcelFileDescriptor
                    var valid = false
                    try {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                            valid = afd.declaredLength > 0 || afd.length > 0 || afd.fileDescriptor.valid()
                        }
                    } catch (_: Exception) {
                        try {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                valid = pfd.statSize > 0 || pfd.fileDescriptor.valid()
                            }
                        } catch (_: Exception) {
                            valid = false
                        }
                    }
                    valid
                }

                "file" -> {
                    val uriStr = uri.toString()
                    if (uriStr.startsWith("file:///android_asset/")) {
                        val assetPath = uriStr.removePrefix("file:///android_asset/")
                        try {
                            context.assets.openFd(assetPath).use { afd ->
                                afd.length > 0
                            }
                        } catch (_: Exception) {
                            false
                        }
                    } else {
                        val path = uri.path
                        if (!path.isNullOrBlank()) {
                            val f = File(path)
                            f.exists() && f.canRead() && f.length() > 0
                        } else {
                            false
                        }
                    }
                }

                // Web URLs (Twitch, YouTube, etc.) and custom schemes (e.g. cortaja://, mock://)
                // cannot be parsed directly by native NuMediaExtractor without downloading
                else -> false
            }
        } catch (e: Exception) {
            Log.d(TAG, "isReadableMediaSource check failed: ${e.message}")
            false
        }
    }

    /**
     * Creates and initializes a MediaExtractor ONLY if the data source is readable.
     * Returns null safely without triggering NuMediaExtractor native error logs.
     */
    fun createExtractor(context: Context, uri: Uri): MediaExtractor? {
        if (!isReadableMediaSource(context, uri)) {
            return null
        }

        val extractor = MediaExtractor()
        return try {
            when (uri.scheme) {
                "content" -> {
                    var configured = false
                    try {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            configured = true
                        }
                    } catch (_: Exception) {}

                    if (!configured) {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            extractor.setDataSource(pfd.fileDescriptor)
                            configured = true
                        }
                    }

                    if (configured) extractor else {
                        extractor.release()
                        null
                    }
                }

                "file" -> {
                    val uriStr = uri.toString()
                    if (uriStr.startsWith("file:///android_asset/")) {
                        val assetPath = uriStr.removePrefix("file:///android_asset/")
                        context.assets.openFd(assetPath).use { afd ->
                            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        }
                        extractor
                    } else {
                        val path = uri.path
                        if (path != null && File(path).exists()) {
                            extractor.setDataSource(path)
                            extractor
                        } else {
                            extractor.release()
                            null
                        }
                    }
                }

                else -> {
                    extractor.release()
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "createExtractor exception: ${e.message}")
            try { extractor.release() } catch (_: Exception) {}
            null
        }
    }

    /**
     * Creates and initializes a MediaMetadataRetriever ONLY if the data source is readable.
     * Returns null safely if not available.
     */
    fun createRetriever(context: Context, uri: Uri): MediaMetadataRetriever? {
        if (!isReadableMediaSource(context, uri)) {
            return null
        }

        val retriever = MediaMetadataRetriever()
        return try {
            when (uri.scheme) {
                "content" -> {
                    var configured = false
                    try {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            configured = true
                        }
                    } catch (_: Exception) {}

                    if (!configured) {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor)
                            configured = true
                        }
                    }

                    if (configured) retriever else {
                        try { retriever.release() } catch (_: Exception) {}
                        null
                    }
                }

                "file" -> {
                    val uriStr = uri.toString()
                    if (uriStr.startsWith("file:///android_asset/")) {
                        val assetPath = uriStr.removePrefix("file:///android_asset/")
                        context.assets.openFd(assetPath).use { afd ->
                            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        }
                        retriever
                    } else {
                        val path = uri.path
                        if (path != null && File(path).exists()) {
                            retriever.setDataSource(path)
                            retriever
                        } else {
                            try { retriever.release() } catch (_: Exception) {}
                            null
                        }
                    }
                }

                else -> {
                    try { retriever.release() } catch (_: Exception) {}
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "createRetriever exception: ${e.message}")
            try { retriever.release() } catch (_: Exception) {}
            null
        }
    }
}
