package com.janus.app.core

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Manages scanning, thumbnailing, and streaming of phone media files
 * (Photos, Videos, Downloads) to connected Mac client over WebSocket.
 */
class MediaManager(private val context: Context) {

    companion object {
        private const val TAG = "JanusMediaManager"
        private const val CHUNK_SIZE = 64 * 1024 // 64 KB per chunk
    }

    /**
     * Lists media files matching the given category ("all", "image", "video", "download")
     */
    fun listMedia(category: String = "all", limit: Int = 100, offset: Int = 0): JsonObject {
        val root = JsonObject()
        val items = JsonArray()

        try {
            val resolver = context.contentResolver

            // 1. Scan Images
            if (category == "all" || category == "image" || category == "photos") {
                val imageProjection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.MIME_TYPE
                )
                val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC LIMIT $limit OFFSET $offset"
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageProjection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                    var count = 0
                    while (cursor.moveToNext() && count < limit) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "image_$id.jpg"
                        val size = cursor.getLong(sizeCol)
                        val dateModified = cursor.getLong(dateCol)
                        val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                        val item = JsonObject().apply {
                            addProperty("id", id.toString())
                            addProperty("name", name)
                            addProperty("size", size)
                            addProperty("date_modified", dateModified)
                            addProperty("mime_type", mime)
                            addProperty("category", "image")
                        }

                        // Generate small thumbnail for fast preview
                        if (count < 30) {
                            val thumb = generateThumbnail(contentUri, isVideo = false)
                            if (thumb != null) {
                                item.addProperty("thumbnail", thumb)
                            }
                        }

                        items.add(item)
                        count++
                    }
                }
            }

            // 2. Scan Videos
            if (category == "all" || category == "video" || category == "videos") {
                val videoProjection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.MIME_TYPE,
                    MediaStore.Video.Media.DURATION
                )
                val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC LIMIT $limit OFFSET $offset"
                resolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoProjection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                    val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)

                    var count = 0
                    while (cursor.moveToNext() && count < limit) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "video_$id.mp4"
                        val size = cursor.getLong(sizeCol)
                        val dateModified = cursor.getLong(dateCol)
                        val mime = cursor.getString(mimeCol) ?: "video/mp4"
                        val duration = if (durCol != -1) cursor.getLong(durCol) else 0L
                        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                        val item = JsonObject().apply {
                            addProperty("id", id.toString())
                            addProperty("name", name)
                            addProperty("size", size)
                            addProperty("date_modified", dateModified)
                            addProperty("mime_type", mime)
                            addProperty("category", "video")
                            addProperty("duration_ms", duration)
                        }

                        if (count < 20) {
                            val thumb = generateThumbnail(contentUri, isVideo = true)
                            if (thumb != null) {
                                item.addProperty("thumbnail", thumb)
                            }
                        }

                        items.add(item)
                        count++
                    }
                }
            }

            // 3. Scan Downloads (Android 10+)
            if ((category == "all" || category == "download" || category == "downloads" || category == "documents") &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                val downloadProjection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_MODIFIED,
                    MediaStore.Downloads.MIME_TYPE
                )
                val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC LIMIT $limit OFFSET $offset"
                try {
                    resolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        downloadProjection,
                        null,
                        null,
                        sortOrder
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)

                        var count = 0
                        while (cursor.moveToNext() && count < limit) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: "download_$id"
                            val size = cursor.getLong(sizeCol)
                            val dateModified = cursor.getLong(dateCol)
                            val mime = cursor.getString(mimeCol) ?: "application/octet-stream"

                            val item = JsonObject().apply {
                                addProperty("id", id.toString())
                                addProperty("name", name)
                                addProperty("size", size)
                                addProperty("date_modified", dateModified)
                                addProperty("mime_type", mime)
                                addProperty("category", "download")
                            }
                            items.add(item)
                            count++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Downloads query skipped or failed", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error listing media", e)
            root.addProperty("error", e.message ?: "Failed to list media")
        }

        root.add("items", items)
        root.addProperty("count", items.size())
        root.addProperty("category", category)
        return root
    }

    /**
     * Streams a media file back chunk by chunk via onChunk callback
     */
    fun fetchMedia(
        mediaId: Long,
        category: String,
        fetchId: String,
        onChunk: (chunkIndex: Int, totalChunks: Int, base64Data: String, fileName: String, mimeType: String) -> Unit,
        onDone: (fileName: String, totalBytes: Long) -> Unit,
        onError: (String) -> Unit
    ) {
        val resolver = context.contentResolver
        val contentUri: Uri = when (category.lowercase()) {
            "video", "videos" -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
            "download", "downloads", "document", "documents" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, mediaId)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
                }
            }
            else -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        }

        var fileName = "file_$mediaId"
        var mimeType = "application/octet-stream"
        var totalSize = 0L

        // Query file metadata first
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE
            )
            resolver.query(contentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                    if (nameIdx != -1) fileName = cursor.getString(nameIdx) ?: fileName
                    if (sizeIdx != -1) totalSize = cursor.getLong(sizeIdx)
                    if (mimeIdx != -1) mimeType = cursor.getString(mimeIdx) ?: mimeType
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read media metadata, continuing with fallback", e)
        }

        var inputStream: InputStream? = null
        try {
            inputStream = resolver.openInputStream(contentUri)
            if (inputStream == null) {
                onError("Unable to open stream for URI: $contentUri")
                return
            }

            val totalChunks = if (totalSize > 0) {
                ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1)
            } else {
                -1
            }

            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            var chunkIndex = 0
            var cumulativeBytes = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                val chunkBytes = if (bytesRead == CHUNK_SIZE) buffer else buffer.copyOf(bytesRead)
                val base64Data = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)
                cumulativeBytes += bytesRead

                onChunk(chunkIndex, totalChunks, base64Data, fileName, mimeType)
                chunkIndex++
            }

            onDone(fileName, cumulativeBytes)
            Log.d(TAG, "Media fetch completed for $fileName ($cumulativeBytes bytes, $chunkIndex chunks)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream media: ${e.message}", e)
            onError(e.message ?: "Stream error")
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
        }
    }

    private fun generateThumbnail(uri: Uri, isVideo: Boolean): String? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(128, 128), null)
            } else {
                null
            }

            if (bitmap != null) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                val bytes = stream.toByteArray()
                bitmap.recycle()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
