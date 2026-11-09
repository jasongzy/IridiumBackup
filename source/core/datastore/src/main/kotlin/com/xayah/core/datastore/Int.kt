package com.xayah.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import com.xayah.core.datastore.ConstantUtil.DEFAULT_IDLE_TIMEOUT

// -----------------------------------------Keys-----------------------------------------
val KeyScreenOffTimeout = intPreferencesKey("screen_off_timeout")
val KeyCompressionLevel = intPreferencesKey("compression_level")


// -----------------------------------------Read-----------------------------------------
fun Context.readScreenOffTimeout() = readStoreInt(key = KeyScreenOffTimeout, defValue = DEFAULT_IDLE_TIMEOUT)
internal const val DEFAULT_COMPRESSION_LEVEL = 1
fun Context.readCompressionLevel() = readStoreInt(key = KeyCompressionLevel, defValue = DEFAULT_COMPRESSION_LEVEL)


// -----------------------------------------Write-----------------------------------------
suspend fun Context.saveScreenOffTimeout(value: Int) = saveStoreInt(key = KeyScreenOffTimeout, value = value)
suspend fun Context.saveCompressionLevel(value: Int) = saveStoreInt(key = KeyCompressionLevel, value = value)
