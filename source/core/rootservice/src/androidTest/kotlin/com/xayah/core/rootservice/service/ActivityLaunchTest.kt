package com.xayah.core.rootservice.service

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.rootservice.IRemoteRootService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLaunchTest {
    @Test
    fun rootFallbackOpensOnlyTheRequestedUser() = runBlocking {
        val localActions = mutableListOf<String?>()
        val rootUsers = mutableListOf<Int>()
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) { localActions += intent.action }
        }
        val binder = Binder()
        val remote = object : IRemoteRootService.Default() {
            override fun asBinder() = binder
            override fun startActivity(intent: Intent, userId: Int): Boolean {
                rootUsers += userId
                return false
            }
        }
        val service = RemoteRootService(context)
        service.javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(service, remote)
        val local = Process.myUid() / 100_000
        val other = if (local == 0) 10 else 0
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=test.app"))
        assertFalse(service.startActivity(market, other))
        assertTrue(service.startActivity(market, local))
        assertTrue(service.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), local))
        assertEquals(listOf(other, local), rootUsers)
        assertEquals(listOf(Intent.ACTION_VIEW, Settings.ACTION_APPLICATION_DETAILS_SETTINGS), localActions)
    }
}
