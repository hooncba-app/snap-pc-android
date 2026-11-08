package com.snapandsort.pcbridge

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Optional read-only bridge. A missing or unprovable source yields no digest. */
object SnapPcOriginalHash {
    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun read(resolver: ContentResolver, call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("asset_id")?.toLongOrNull()
        val name = call.argument<String>("name")
        val created = call.argument<Number>("created_at")?.toLong()
        val modified = call.argument<Number>("modified_at")?.toLong()
        background.execute {
            val value = try {
                if (id == null || id <= 0 || name.isNullOrEmpty() || created == null || created <= 0) {
                    null
                } else {
                    proof(resolver, id, name, created, modified)
                }
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            main.post { result.success(value) }
        }
    }

    private data class Media(
        val volume: String, val kind: Int, val name: String, val size: Long,
        val taken: Long, val added: Long, val modified: Long, val generation: Long?
    )

    private fun lookup(resolver: ContentResolver, id: Long): Media? {
        val columns = mutableListOf("_id", "volume_name", "media_type", "_display_name", "_size",
            "datetaken", "date_added", "date_modified")
        if (Build.VERSION.SDK_INT >= 30) columns.add("generation_modified")
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return resolver.query(files, columns.toTypedArray(), "_id=?", arrayOf(id.toString()), null)?.use {
            // Ambiguous across mounted volumes or no longer accessible.
            if (it.count != 1 || !it.moveToFirst()) return@use null
            val volume = it.getString(1) ?: return@use null
            val name = it.getString(3) ?: return@use null
            Media(volume, it.getInt(2), name, it.getLong(4), it.getLong(5),
                it.getLong(6), it.getLong(7), if (columns.size == 9) it.getLong(8) else null)
        }
    }

    private fun proof(resolver: ContentResolver, id: Long, name: String, created: Long,
                      modified: Long?): Map<String, Any>? {
        if (Build.VERSION.SDK_INT < 29) return null
        val row = lookup(resolver, id) ?: return null
        if (row.name != name || row.size <= 0 ||
            created / 1000 != row.taken / 1000 && created / 1000 != row.added ||
            modified != null && modified > 0 && modified / 1000 != row.modified) return null
        val collection: Uri = when (row.kind) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(row.volume)
            // A compatibility-transcoded video is not proof of the original.
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> return null
            else -> return null
        }
        val uri = MediaStore.setRequireOriginal(ContentUris.withAppendedId(collection, id))
        val fd = resolver.openFileDescriptor(uri, "r") ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size += read
                if (size > row.size) return null
                digest.update(buffer, 0, read)
            }
        }
        if (size != row.size || lookup(resolver, id) != row) return null
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return mapOf("sha256" to hex, "byte_size" to size)
    }
}
