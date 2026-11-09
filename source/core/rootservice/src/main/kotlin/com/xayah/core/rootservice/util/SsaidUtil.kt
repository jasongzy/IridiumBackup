// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-or-later

package com.xayah.core.rootservice.util

import android.os.Build
import android.os.HandlerThread
import android.os.Process
import android.os.UserHandle
import android.os.UserHandleHidden
import android.util.Xml
import android.util.XmlApi30
import com.android.internal.util.BinaryXmlSerializer.PROTOCOL_MAGIC_VERSION_0
import com.android.providers.settings.SettingsState
import com.android.providers.settings.SettingsStateApi26
import com.android.providers.settings.SettingsStateApi31
import com.xayah.core.util.PathUtil
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File

/**
 * @author <a href="https://github.com/MuntashirAkon">@MuntashirAkon</a>
 */
internal class SsaidUtil(private val userId: Int, private val file: File = File(PathUtil.getSsaidPath(userId))) {
    private fun isApp(packageName: String, uid: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageName != SettingsState.SYSTEM_PACKAGE_NAME &&
            Process.isApplicationUid(uid) && UserHandle.getUserHandleForUid(uid) == UserHandleHidden.of(userId)

    fun getSsaid(packageName: String, uid: Int): String? {
        if (!isApp(packageName, uid) || !file.exists()) return null
        val name = uid.toString()
        return file.inputStream().buffered().use { input ->
            val parser = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && input.hasBinaryXmlHeader()) {
                XmlApi30.newBinaryPullParser()
            } else {
                Xml.newPullParser()
            }
            parser.setInput(input, "UTF-8")
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "setting" &&
                    parser.getAttributeValue(null, "name") == name
                ) return@use parser.getAttributeValue(null, "value")
            }
            null
        }
    }

    private fun BufferedInputStream.hasBinaryXmlHeader(): Boolean {
        mark(PROTOCOL_MAGIC_VERSION_0.size)
        val matches = PROTOCOL_MAGIC_VERSION_0.all { read() == it.toInt() }
        reset()
        return matches
    }

    fun setSsaid(packageName: String, uid: Int, ssaid: String) {
        if (!isApp(packageName, uid)) return
        val lock = Any()
        val key = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_SSAID, userId)
        val quota = SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SettingsStateApi31(lock, file, key, quota, writeLooper)
        } else {
            SettingsStateApi26(lock, file, key, quota, writeLooper)
        }
        synchronized(lock) { state.insertSettingLocked(uid.toString(), ssaid, null, true, packageName) }
    }

    private companion object {
        val writeLooper by lazy {
            HandlerThread("ssaid_writer", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper
        }
    }
}
