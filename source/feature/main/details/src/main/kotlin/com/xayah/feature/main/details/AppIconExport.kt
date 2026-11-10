package com.xayah.feature.main.details

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

private const val ICON_EXPORT_DIR = "IridiumBackup"

internal fun Context.exportAppIcon(bitmap: Bitmap, packageName: String): Boolean {
    val fileName = "${packageName}_${System.currentTimeMillis()}.png"
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return saveLegacyIcon(bitmap, fileName)
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ICON_EXPORT_DIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        var published = false
        try {
            published = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true &&
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1
            published
        } finally {
            if (!published) resolver.delete(uri, null, null)
        }
    } catch (_: Exception) {
        false
    }
}

@Suppress("DEPRECATION")
private fun Context.saveLegacyIcon(bitmap: Bitmap, fileName: String): Boolean = runCatching {
    val directory = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        ICON_EXPORT_DIR,
    )
    if (directory.exists().not() && directory.mkdirs().not()) return@runCatching false
    val file = File(directory, fileName)
    if (!file.createNewFile()) return@runCatching false
    var saved = false
    try {
        saved = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (saved) MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf("image/png"), null)
        saved
    } finally {
        if (!saved) file.delete()
    }
}.getOrDefault(false)
