package com.xayah.core.ui.component

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.lifecycle.ViewModel
import com.xayah.core.database.dao.AppBackupDao
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import com.xayah.core.datastore.readBackupSavePath
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltViewModel
class PackageIconViewModel @Inject constructor(val icons: PackageIconLoader) : ViewModel()

class PackageIconLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val dao: AppBackupDao,
) {
    private val cache = LruCache<String, Drawable>(128)

    suspend fun load(packageName: String, userId: Int?, refresh: Boolean = false): Drawable? = withContext(Dispatchers.IO) {
        rootService.getInstalledIcon(packageName, userId)?.let { return@withContext it }
        val current = context.readBackupSavePath().first()
        suspend fun read(dir: String): Drawable? {
            val path = PathUtil.getAppIconPath("$dir/${PathUtil.getAppsRelativeDir()}", packageName)
            if (refresh) cache.remove(path) else cache.get(path)?.let { return it }
            return rootService.readIcon(path)?.also { cache.put(path, it) }
        }
        read(current) ?: dao.localBackupDirs(packageName, userId).firstNotNullOfOrNull { dir ->
            if (dir == current) null else read(dir)
        }
    }
}
