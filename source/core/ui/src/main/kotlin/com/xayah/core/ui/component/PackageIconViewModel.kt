package com.xayah.core.ui.component

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.lifecycle.ViewModel
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import com.xayah.core.datastore.readBackupSavePath
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class PackageIconViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
) : ViewModel() {
    private val cache = LruCache<String, Drawable>(128)
    private val mutex = Mutex()

    suspend fun load(packageName: String, userId: Int?, refresh: Boolean): Drawable? = withContext(Dispatchers.IO) {
        rootService.getInstalledIcon(packageName, userId)?.let { return@withContext it }
        mutex.withLock {
            val appsDir = "${context.readBackupSavePath().first()}/${PathUtil.getAppsRelativeDir()}"
            val path = PathUtil.getAppIconPath(appsDir, packageName)
            if (refresh) cache.remove(path) else cache.get(path)?.let { return@withLock it }
            rootService.readIcon(path)?.also {
                cache.put(path, it)
            }
        }
    }
}
