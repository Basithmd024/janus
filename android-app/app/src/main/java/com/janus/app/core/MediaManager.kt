package com.janus.app.core

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Secure application-level Media & File Manager for Janus.
 * Uses Android MediaStore and ContentResolver APIs.
 * Returns only media metadata rather than raw filesystem paths.
 * Streams file content using ParcelFileDescriptor and byte-range chunks.
 */
class MediaManager(private val context: Context) {

    companion object {
        private const val TAG = "JanusMediaManager"
        const val DEFAULT_CHUNK_SIZE = 256 * 1024 // 256 KB per chunk
        const val MAX_RANGE_SIZE = 10 * 1024 * 1024L // 10 MB per range
    }

    private val activeTransfers = ConcurrentHashMap.newKeySet<String>()
    private val cancelledTransfers = ConcurrentHashMap.newKeySet<String>()

    /**
     * Lists media files matching category ("all", "photo", "photos", "screenshot", "screenshots", "video", "videos", "audio", "music", "download", "downloads").
     * Supports incremental delta querying via `since` (Unix timestamp in seconds).
     */
    fun listMedia(
        category: String = "all",
        limit: Int = 300,
        offset: Int = 0,
        since: Long = 0L
    ): JsonObject {
        val root = JsonObject()
        val items = JsonArray()

        val catLower = category.lowercase().trim()
        val isAll = catLower == "all" || catLower.isBlank()
        val isScreenshotQuery = catLower == "screenshot" || catLower == "screenshots"
        val isPhotoQuery = catLower == "photo" || catLower == "photos"
        val isImageQuery = isAll || catLower == "image" || catLower == "images" || isPhotoQuery || isScreenshotQuery
        val isVideoQuery = isAll || catLower == "video" || catLower == "videos"
        val isAudioQuery = isAll || catLower == "audio" || catLower == "music"
        val isDownloadQuery = isAll || catLower == "download" || catLower == "downloads" || catLower == "document" || catLower == "documents"

        // ── 1. Check Permissions Flexibly ──────────────────────────
        val hasAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val hasLegacyStorage = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

        val hasImagePerm = hasAllFilesAccess || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLegacyStorage
        }

        val hasVideoPerm = hasAllFilesAccess || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLegacyStorage
        }

        val hasAudioPerm = hasAllFilesAccess || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLegacyStorage
        }

        // Android 10+ MediaStore.Downloads requires NO runtime permission for public downloads
        val hasDownloadPerm = hasAllFilesAccess || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasLegacyStorage

        val isAllowed = when {
            isAll -> hasImagePerm || hasVideoPerm || hasAudioPerm || hasDownloadPerm
            isImageQuery -> hasImagePerm
            isVideoQuery -> hasVideoPerm
            isAudioQuery -> hasAudioPerm
            isDownloadQuery -> hasDownloadPerm
            else -> true
        }

        if (!isAllowed) {
            Log.w(TAG, "Permission denied for media category: $category")
            root.addProperty("error", "PERMISSION_DENIED")
            root.addProperty("error_message", "Photos & Files permission not granted on phone. Please allow permission in Janus app.")
            root.add("items", items)
            root.addProperty("count", 0)
            root.addProperty("category", category)
            return root
        }

        val resolver = context.contentResolver

        // ── 2. Scan Images & Photos ────────────────────────────────
        if (isImageQuery && hasImagePerm) {
            val imageProjection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            var selection = if (since > 0) "${MediaStore.Images.Media.DATE_MODIFIED} > $since" else null
            var selectionArgs: Array<String>? = null

            if (isScreenshotQuery) {
                val scClause = "(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
                selection = if (selection != null) "$selection AND $scClause" else scClause
                selectionArgs = arrayOf("%Screenshot%", "%Screenshot%")
            } else if (isPhotoQuery) {
                val phClause = "(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IS NULL OR (${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} NOT LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} NOT LIKE ?))"
                selection = if (selection != null) "$selection AND $phClause" else phClause
                selectionArgs = arrayOf("%Screenshot%", "%Screenshot%")
            }

            try {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageProjection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                    val wCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val hCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                    val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                    if (cursor.moveToPosition(offset)) {
                        var count = 0
                        val maxToTake = if (isAll) limit.coerceAtMost(250) else limit
                        do {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: "image_$id.jpg"
                            val size = cursor.getLong(sizeCol)
                            val dateModified = cursor.getLong(dateCol)
                            val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                            val width = if (wCol != -1) cursor.getInt(wCol) else 0
                            val height = if (hCol != -1) cursor.getInt(hCol) else 0
                            val bucketName = if (bucketCol != -1) cursor.getString(bucketCol) ?: "" else ""
                            val isScreenshot = bucketName.contains("Screenshot", ignoreCase = true) ||
                                               name.contains("Screenshot", ignoreCase = true) ||
                                               name.contains("Screen_Shot", ignoreCase = true)

                            val itemCategory = if (isScreenshot) "screenshot" else "photo"

                            val item = JsonObject().apply {
                                addProperty("mediaId", id.toString())
                                addProperty("name", name)
                                addProperty("mimeType", mime)
                                addProperty("size", size)
                                addProperty("dateModified", dateModified)
                                if (width > 0) addProperty("width", width)
                                if (height > 0) addProperty("height", height)
                                addProperty("category", itemCategory)
                            }

                            items.add(item)
                            count++
                            if (count >= maxToTake) break
                        } while (cursor.moveToNext())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying images MediaStore", e)
            }
        }

        // ── 3. Scan Videos ─────────────────────────────────────────
        if (isVideoQuery && hasVideoPerm) {
            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DURATION
            )
            val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            val selection = if (since > 0) "${MediaStore.Video.Media.DATE_MODIFIED} > $since" else null
            try {
                resolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoProjection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                    val wCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                    val hCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                    val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)

                    if (cursor.moveToPosition(offset)) {
                        var count = 0
                        val maxToTake = if (isAll) limit.coerceAtMost(150) else limit
                        do {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: "video_$id.mp4"
                            val size = cursor.getLong(sizeCol)
                            val dateModified = cursor.getLong(dateCol)
                            val mime = cursor.getString(mimeCol) ?: "video/mp4"
                            val width = if (wCol != -1) cursor.getInt(wCol) else 0
                            val height = if (hCol != -1) cursor.getInt(hCol) else 0
                            val duration = if (durCol != -1) cursor.getLong(durCol) else 0L

                            val item = JsonObject().apply {
                                addProperty("mediaId", id.toString())
                                addProperty("name", name)
                                addProperty("mimeType", mime)
                                addProperty("size", size)
                                addProperty("dateModified", dateModified)
                                if (width > 0) addProperty("width", width)
                                if (height > 0) addProperty("height", height)
                                if (duration > 0) addProperty("durationMs", duration)
                                addProperty("category", "video")
                            }

                            items.add(item)
                            count++
                            if (count >= maxToTake) break
                        } while (cursor.moveToNext())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying video MediaStore", e)
            }
        }

        // ── 4. Scan Audio & Music ──────────────────────────────────
        if (isAudioQuery && hasAudioPerm) {
            val audioProjection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ARTIST
            )
            val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            val selection = if (since > 0) "${MediaStore.Audio.Media.DATE_MODIFIED} > $since" else null
            try {
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    audioProjection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                    val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)

                    if (cursor.moveToPosition(offset)) {
                        var count = 0
                        val maxToTake = if (isAll) limit.coerceAtMost(150) else limit
                        do {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: "audio_$id.mp3"
                            val size = cursor.getLong(sizeCol)
                            val dateModified = cursor.getLong(dateCol)
                            val mime = cursor.getString(mimeCol) ?: "audio/mpeg"
                            val duration = if (durCol != -1) cursor.getLong(durCol) else 0L
                            val artist = if (artistCol != -1) cursor.getString(artistCol) ?: "" else ""

                            val item = JsonObject().apply {
                                addProperty("mediaId", id.toString())
                                addProperty("name", name)
                                addProperty("mimeType", mime)
                                addProperty("size", size)
                                addProperty("dateModified", dateModified)
                                if (duration > 0) addProperty("durationMs", duration)
                                if (artist.isNotBlank() && artist != "<unknown>") addProperty("artist", artist)
                                addProperty("category", "audio")
                            }

                            items.add(item)
                            count++
                            if (count >= maxToTake) break
                        } while (cursor.moveToNext())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying audio MediaStore", e)
            }
        }

        // ── 5. Scan Downloads & Documents ──────────────────────────
        if (isDownloadQuery && hasDownloadPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val downloadProjection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
                MediaStore.Downloads.MIME_TYPE
            )
            val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            val selection = if (since > 0) "${MediaStore.Downloads.DATE_MODIFIED} > $since" else null
            try {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    downloadProjection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)

                    if (cursor.moveToPosition(offset)) {
                        var count = 0
                        val maxToTake = if (isAll) limit.coerceAtMost(150) else limit
                        do {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: "download_$id"
                            val size = cursor.getLong(sizeCol)
                            val dateModified = cursor.getLong(dateCol)
                            val mime = cursor.getString(mimeCol) ?: "application/octet-stream"

                            val item = JsonObject().apply {
                                addProperty("mediaId", id.toString())
                                addProperty("name", name)
                                addProperty("mimeType", mime)
                                addProperty("size", size)
                                addProperty("dateModified", dateModified)
                                addProperty("category", "download")
                            }
                            items.add(item)
                            count++
                            if (count >= maxToTake) break
                        } while (cursor.moveToNext())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying downloads MediaStore", e)
            }
        }

        root.add("items", items)
        root.addProperty("count", items.size())
        root.addProperty("category", category)
        root.addProperty("syncToken", System.currentTimeMillis() / 1000)
        Log.d(TAG, "Indexed ${items.size()} media items for category '$category'")
        return root
    }

    /**
     * Lazily extracts and returns a micro-thumbnail for a specific media item.
     */
    fun getThumbnail(mediaId: Long, category: String, width: Int = 128, height: Int = 128): String? {
        val contentUri = getUriForMedia(mediaId, category) ?: return null
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(contentUri, Size(width, height), null)
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
        } catch (e: Throwable) {
            Log.w(TAG, "Thumbnail generation failed for media $mediaId", e)
            null
        }
    }

    /**
     * Reads a byte range from a media file using ContentResolver.openFileDescriptor().
     * Does not load complete file into memory.
     * Computes SHA-256 in real time and streams in 256KB chunks.
     * Supports cancellation via `cancelTransfer(requestId)`.
     */
    fun getFileRange(
        requestId: String,
        mediaId: Long,
        category: String,
        offset: Long,
        requestedLength: Long,
        onInfo: (name: String, mimeType: String, totalSize: Long, rangeLength: Long) -> Unit,
        onChunk: (chunkIndex: Int, totalChunks: Int, base64Data: String, dataLength: Int, isLast: Boolean) -> Unit,
        onComplete: (totalBytes: Long, sha256Hex: String) -> Unit,
        onError: (error: String) -> Unit
    ) {
        activeTransfers.add(requestId)
        cancelledTransfers.remove(requestId)

        val contentUri = getUriForMedia(mediaId, category)
        if (contentUri == null) {
            onError("Unknown media category: $category")
            activeTransfers.remove(requestId)
            return
        }

        var pfd: ParcelFileDescriptor? = null
        var fis: FileInputStream? = null

        try {
            pfd = context.contentResolver.openFileDescriptor(contentUri, "r")
            if (pfd == null) {
                onError("Unable to open file descriptor for media ID $mediaId")
                activeTransfers.remove(requestId)
                return
            }

            val totalSize = pfd.statSize

            // Retrieve display name and mime type
            var displayName = "file_$mediaId"
            var mimeType = "application/octet-stream"
            context.contentResolver.query(
                contentUri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    if (nameIdx != -1) displayName = cursor.getString(nameIdx) ?: displayName
                    if (mimeIdx != -1) mimeType = cursor.getString(mimeIdx) ?: mimeType
                }
            }

            if (offset >= totalSize && totalSize > 0) {
                onError("Offset $offset is out of bounds for file of size $totalSize")
                activeTransfers.remove(requestId)
                return
            }

            val actualLength = if (requestedLength <= 0 || offset + requestedLength > totalSize) {
                (totalSize - offset).coerceAtLeast(0)
            } else {
                requestedLength.coerceAtMost(MAX_RANGE_SIZE)
            }

            onInfo(displayName, mimeType, totalSize, actualLength)

            if (actualLength == 0L) {
                onComplete(0L, "")
                activeTransfers.remove(requestId)
                return
            }

            fis = FileInputStream(pfd.fileDescriptor)
            // Seek to offset
            if (offset > 0) {
                fis.channel.position(offset)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
            var bytesRemaining = actualLength
            var chunkIndex = 0
            val totalChunks = ((actualLength + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE).toInt().coerceAtLeast(1)
            var totalRead = 0L

            while (bytesRemaining > 0) {
                if (cancelledTransfers.contains(requestId)) {
                    Log.d(TAG, "Transfer $requestId cancelled by client")
                    onError("Transfer cancelled")
                    return
                }

                val toRead = bytesRemaining.coerceAtMost(DEFAULT_CHUNK_SIZE.toLong()).toInt()
                val bytesRead = fis.read(buffer, 0, toRead)
                if (bytesRead == -1) break

                digest.update(buffer, 0, bytesRead)
                val chunkBytes = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                val base64Data = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)

                bytesRemaining -= bytesRead
                totalRead += bytesRead
                val isLast = bytesRemaining == 0L || chunkIndex == totalChunks - 1

                onChunk(chunkIndex, totalChunks, base64Data, bytesRead, isLast)
                chunkIndex++
            }

            val sha256Bytes = digest.digest()
            val sha256Hex = sha256Bytes.joinToString("") { "%02x".format(it) }

            onComplete(totalRead, sha256Hex)
            Log.d(TAG, "Completed range transfer $requestId: $totalRead bytes (SHA-256: $sha256Hex)")

        } catch (e: Exception) {
            Log.e(TAG, "Error in getFileRange for media $mediaId: ${e.message}", e)
            onError(e.message ?: "File transfer error")
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            activeTransfers.remove(requestId)
            cancelledTransfers.remove(requestId)
        }
    }

    /**
     * Cancels an ongoing file transfer.
     */
    fun cancelTransfer(requestId: String) {
        cancelledTransfers.add(requestId)
        Log.d(TAG, "Marked transfer $requestId for cancellation")
    }

    private fun getUriForMedia(mediaId: Long, category: String): Uri? {
        return when (category.lowercase()) {
            "video", "videos" -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
            "audio", "music" -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
            "download", "downloads", "document", "documents" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, mediaId)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
                }
            }
            "photo", "photos", "screenshot", "screenshots", "image", "images", "all" -> {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
            }
            else -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        }
    }
}
