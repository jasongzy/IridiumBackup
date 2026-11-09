package com.xayah.core.service.util

import com.xayah.core.util.archive.finishArchiveStep
import android.content.Context
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCleanRestoring
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class MediumRestoreUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val mediaRepository: MediaRepository,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "MediumRestoreUtil"
    }

    private fun log(onMsg: () -> String) = LogUtil.log(TAG, onMsg())

    private suspend fun TaskDetailMediaEntity.updateInfo(
        state: OperationState? = null,
        bytes: Long? = null,
        log: String? = null,
        content: String? = null,
    ) = run {
        mediaInfo.also {
            if (state != null) it.state = state
            if (bytes != null) it.bytes = bytes
            if (log != null) it.log = log
            if (content != null) it.content = content
        }
        taskDao.upsert(this)
    }

    suspend fun restoreMedia(m: MediaEntity, t: TaskDetailMediaEntity, srcDir: String): ShellResult = finishArchiveStep {
        log { "Restoring ${DataType.MEDIA_MEDIA.type}..." }

        val ct = m.indexInfo.compressionType
        val src = mediaRepository.getArchiveDst(dstDir = srcDir, ct = ct)
        val dst = m.path
        val dstDir = PathUtil.getParentPath(dst)
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        // Return if the archive doesn't exist.
        if (rootService.exists(src)) {
            val sizeBytes = rootService.calculateSize(src)
            t.updateInfo(state = OperationState.PROCESSING, bytes = sizeBytes)

            val linkDir = "${context.cacheDir}/restore-links-${UUID.randomUUID()}"
            try {
                val extraction = rootService.extractArchive(
                    source = src,
                    destination = dstDir,
                    compression = ct.decompressPara,
                    workspace = linkDir,
                    cleanDestination = dst.takeIf { context.readCleanRestoring().first() }.orEmpty(),
                    requiredPrefix = PathUtil.getFileName(dst),
                    ignoreModificationTime = false,
                )
                isSuccess = extraction.result.isSuccess
                out.addAll(extraction.result.out)
                if (isSuccess) {
                    val restoredLinks = if (extraction.pendingLinks == 0) 0 else rootService.restoreArchiveLinks(linkDir, dstDir)
                    val skippedLinks = extraction.skippedEntries + extraction.pendingLinks - restoredLinks
                    if (skippedLinks > 0) out.add(log { "Skipped $skippedLinks unsafe or conflicting archive entries." })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isSuccess = false
                out.add(log { e.message ?: e.javaClass.simpleName })
            } finally {
                withContext(NonCancellable) { rootService.deleteRecursively(linkDir) }
            }
        } else {
            isSuccess = false
            out.add(log { "Not exist: $src" })
            t.updateInfo(state = OperationState.ERROR, log = out.toLineString())
            return@finishArchiveStep ShellResult(code = -1, input = listOf(), out = out)
        }
        t.updateInfo(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun download(
        client: CloudClient,
        m: MediaEntity,
        t: TaskDetailMediaEntity,
        srcDir: String,
        onDownloaded: suspend (dir: String) -> Unit
    ) = run {
        val ct = m.indexInfo.compressionType
        val src = mediaRepository.getArchiveDst(dstDir = srcDir, ct = ct)

        t.updateInfo(state = OperationState.DOWNLOADING)
        val result = cloudRepository.download(client, src, onDownloading = { written, _ ->
            t.updateInfo(content = written.toDouble().formatSize())
        }) { onDownloaded(PathUtil.getParentPath(it)) }
        t.updateInfo(
            state = if (result.isSuccess) null else OperationState.ERROR,
            log = (t.mediaInfo.log + "\n" + result.outString).trim(),
        )
    }
}
