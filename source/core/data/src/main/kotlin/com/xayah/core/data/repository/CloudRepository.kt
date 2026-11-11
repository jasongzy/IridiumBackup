package com.xayah.core.data.repository

import android.content.Context
import androidx.annotation.StringRes
import com.xayah.core.database.dao.CloudDao
import com.xayah.core.datastore.readCloudActivatedAccountName
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

class CloudRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val cloudDao: CloudDao,
) {
    private fun log(msg: () -> String): String = run {
        LogUtil.log { "CloudRepository" to msg() }
        msg()
    }

    fun getString(@StringRes resId: Int) = context.getString(resId)
    suspend fun add(item: CloudEntity) = cloudDao.insert(item) != -1L
    suspend fun upsert(item: CloudEntity) = cloudDao.upsert(item)
    suspend fun upsert(items: List<CloudEntity>) = cloudDao.upsert(items)
    suspend fun queryByName(name: String) = cloudDao.queryByName(name)
    suspend fun query() = cloudDao.query()

    val clouds = cloudDao.queryFlow().distinctUntilChanged()

    suspend fun delete(name: String) = cloudDao.delete(name)

    private suspend fun transfer(onProgress: (suspend (Long, Long) -> Unit)?, block: ((Long, Long) -> Unit) -> Unit) = withContext(Dispatchers.IO) {
        if (onProgress == null) return@withContext block { _, _ -> ensureActive() }
        val latest = MutableStateFlow<Pair<Long, Long>?>(null)
        var shown: Pair<Long, Long>? = null
        val reporter = launch {
            latest.filterNotNull().collect { value ->
                onProgress(value.first, value.second)
                shown = value
                delay(500)
            }
        }
        try {
            block { bytes, total ->
                ensureActive()
                latest.value = bytes to total
            }
        } finally {
            reporter.cancelAndJoin()
        }
        latest.value?.takeUnless { it == shown }?.let { (bytes, total) -> onProgress(bytes, total) }
        Unit
    }

    suspend fun upload(client: CloudClient, src: String, dstDir: String, onUploading: (suspend (Int) -> Unit)? = null): ShellResult {
        return try {
            PathUtil.prepareCloudWorkspace(context, src)
            val progress: (suspend (Long, Long) -> Unit)? = onUploading?.let { update ->
                { bytes, total -> update(if (total == 0L) 100 else (100.0 * bytes / total).toInt().coerceIn(0, 100)) }
            }
            transfer(progress) { client.upload(src, dstDir, it) }
            if (!rootService.removeEntry(src)) log { "Unable to release staged file: $src" }
            ShellResult(0, emptyList(), listOf("Uploaded $src"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShellResult(-1, emptyList(), listOf(log { "Upload failed: ${e.message ?: e.javaClass.simpleName}" }))
        }
    }

    suspend fun download(
        client: CloudClient,
        src: String,
        onDownloading: (suspend (Long, Long) -> Unit)? = null,
        onDownloaded: suspend (path: String) -> Unit,
    ): ShellResult {
        val dir = File(context.cacheDir, "cloud-${UUID.randomUUID()}")
        val name = PathUtil.getFileName(src)
        if (name.isEmpty() || name == "." || name == ".." || !dir.mkdir()) {
            return ShellResult(-1, emptyList(), listOf("Unable to stage download: $src"))
        }
        val file = File(dir, name)
        return try {
            transfer(onDownloading) { client.download(src, dir.path, it) }
            onDownloaded(file.path)
            ShellResult(0, emptyList(), listOf("Downloaded $src"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ShellResult(-1, emptyList(), listOf(log { "Download failed: ${e.message ?: e.javaClass.simpleName}" }))
        } finally {
            file.delete()
            dir.delete()
        }
    }

    suspend fun <T : Any> readBackupConfig(client: CloudClient, path: String, type: Class<T>): T {
        var config: T? = null
        val result = download(client, path) { file ->
            config = rootService.gsonUtil.fromJson(File(file).readText(), type)
        }
        check(result.isSuccess) { result.outString }
        return checkNotNull(config) { "Invalid backup config: $path" }
    }

    suspend fun getClient(name: String? = null): Pair<CloudClient, CloudEntity> {
        val account = name ?: context.readCloudActivatedAccountName().first()
        val entity = checkNotNull(queryByName(account)) { "Cloud account not found: $account" }
        check(entity.remote.isNotEmpty()) { "$account: Remote directory is not set." }
        return entity.getCloud().apply { connect() } to entity
    }

    suspend fun withClient(name: String? = null, block: suspend (client: CloudClient, entity: CloudEntity) -> Unit) = run {
        val (client, entity) = getClient(name)
        try {
            block(client, entity)
        } finally {
            client.disconnect()
        }
    }

}
