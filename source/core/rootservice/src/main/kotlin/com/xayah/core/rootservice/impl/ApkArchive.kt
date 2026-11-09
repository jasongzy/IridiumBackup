package com.xayah.core.rootservice.impl

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun exportApks(sources: List<File>, destination: File) {
    require(sources.isNotEmpty()) { "No APK files to export" }
    publishFile(destination) { temporary ->
        FileOutputStream(temporary).use { file ->
            if (destination.extension == "apk") {
                sources.single().inputStream().use { it.copyTo(file) }
                file.fd.sync()
            } else {
                val sharing = destination.extension == "apks"
                val baseName = if (sharing) "base.apk" else "${destination.nameWithoutExtension}.apk"
                val names = listOf(baseName) + sources.drop(1).map(File::getName)
                val uniqueNames = names.toSet().size == names.size
                ZipOutputStream(file.buffered()).use { zip ->
                    zip.setLevel(if (sharing) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
                    sources.forEachIndexed { index, source ->
                        val name = if (index == 0 || uniqueNames) names[index] else "split_$index.apk"
                        zip.putNextEntry(ZipEntry(name))
                        source.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    zip.finish()
                    zip.flush()
                    file.fd.sync()
                }
            }
        }
        if (!temporary.setReadable(true, false)) throw IOException("Unable to make the APK export readable")
    }
}
