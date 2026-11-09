package com.xayah.core.util

import org.apache.commons.codec.digest.DigestUtils
import java.io.File

object HashUtil {
    fun calculateMD5(src: String): String = File(src).inputStream().use { DigestUtils.md5Hex(it) }

    fun calculateSHA256(src: String): String = File(src).inputStream().use { DigestUtils.sha256Hex(it) }
}
