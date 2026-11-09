package com.xayah.core.rootservice.impl

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun writeApkArchive(sources: List<File>, destination: File) {
    require(sources.isNotEmpty()) { "No APK files to archive" }
    publishFile(destination) { temporary ->
        FileOutputStream(temporary).use { file ->
            ZipOutputStream(file.buffered()).use { archive ->
                archive.setLevel(Deflater.BEST_SPEED)
                sources.forEachIndexed { index, source ->
                    val name = if (index == 0) "${destination.nameWithoutExtension}.apk" else source.name
                    archive.putNextEntry(ZipEntry(name))
                    source.inputStream().use { it.copyTo(archive) }
                    archive.closeEntry()
                }
                archive.finish()
                archive.flush()
                file.fd.sync()
            }
        }
        if (!temporary.setReadable(true, false)) throw IOException("Unable to make APK archive readable")
    }
}
