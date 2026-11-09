package com.xayah.core.rootservice.impl

import android.system.Os
import java.io.File

internal fun publishFile(destination: File, write: (File) -> Unit) {
    val target = File(destination.absoluteFile.parentFile!!.canonicalFile, destination.name)
    val temporary = File.createTempFile(".pending-", ".tmp", target.parentFile)
    try {
        write(temporary)
        Os.rename(temporary.path, target.path)
    } finally {
        temporary.delete()
    }
}
