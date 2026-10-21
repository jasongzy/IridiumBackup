package com.xayah.core.rootservice

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.rootservice.service.RemoteRootService
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParcelReadTest {
    @Test
    fun closesDescriptorsAfterSuccessfulAndFailedDecoding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("parcel-read-", ".tmp", context.cacheDir)
        val binder = Binder()
        lateinit var descriptor: ParcelFileDescriptor
        val remote = Proxy.newProxyInstance(
            IRemoteRootService::class.java.classLoader,
            arrayOf(IRemoteRootService::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "asBinder" -> binder
                "readText", "readBytes" -> descriptor
                else -> error("Unexpected root operation: ${method.name}")
            }
        }
        val root = RemoteRootService(context).apply {
            javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(this, remote)
        }
        try {
            for (valid in listOf(true, false)) {
                val parcel = Parcel.obtain()
                try {
                    if (valid) parcel.writeString("metadata") else parcel.writeInt(-1)
                    file.writeBytes(parcel.marshall())
                } finally {
                    parcel.recycle()
                }
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    if (valid) assertEquals("metadata", root.readText("test"))
                    else assertArrayEquals(byteArrayOf(), root.readBytes("test"))
                    assertThrows(IllegalStateException::class.java) { descriptor.fd }
                } finally {
                    descriptor.close()
                }
            }
        } finally {
            file.delete()
        }
    }
}
