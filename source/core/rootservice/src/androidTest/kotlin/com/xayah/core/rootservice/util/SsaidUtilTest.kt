package com.xayah.core.rootservice.util

import android.os.Build
import android.util.Xml
import android.util.XmlApi30
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SsaidUtilTest {
    @Test
    fun onlyTheAppUidCanBeReadFromTheSettingsTable() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("ssaid-", ".xml", cacheDir)
        try {
            val serializers = listOfNotNull(
                Xml.newSerializer(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) XmlApi30.newBinarySerializer() else null,
            )
            for (xml in serializers) {
                file.outputStream().use { out ->
                    xml.setOutput(out, "UTF-8")
                    xml.startDocument(null, true)
                    xml.startTag(null, "settings")
                    for ((name, value) in mapOf("userkey" to "test-seed", "10042" to "1234567890abcdef", "1010042" to "other-user")) {
                        xml.startTag(null, "setting").attribute(null, "name", name).attribute(null, "value", value).endTag(null, "setting")
                    }
                    xml.endTag(null, "settings")
                    xml.endDocument()
                }
                val bytes = file.readBytes()
                val settings = SsaidUtil(0, file)
                assertEquals("1234567890abcdef", settings.getSsaid("test.app", 10042))
                assertNull(settings.getSsaid("android", 1000))
                assertNull(settings.getSsaid("android", 10042))
                assertNull(settings.getSsaid("test.app", 1010042))
                settings.setSsaid("android", 1000, "replacement")
                assertArrayEquals(bytes, file.readBytes())
            }
        } finally {
            file.delete()
        }
    }
}
