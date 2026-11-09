package com.xayah.core.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.xayah.core.database.AppDatabase
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.datastore.readLabelColors
import com.xayah.core.datastore.saveLabelColor
import com.xayah.core.datastore.saveLabelColors
import com.xayah.core.datastore.deleteLabelPreferences
import com.xayah.core.datastore.saveEditedLabel
import com.xayah.core.model.LabelPalette
import com.xayah.core.model.ColoredLabel
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.database.LabelFileCrossRefEntity
import com.xayah.core.util.PathUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class LabelsRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val database: AppDatabase,
) {
    private val labelDao = database.labelDao()

    fun getLabelsFlow(): Flow<List<LabelEntity>> = labelDao.queryLabelsFlow().flowOn(defaultDispatcher)
    fun getColoredLabelsFlow(): Flow<List<ColoredLabel>> = combine(labelDao.queryLabelsFlow(), context.readLabelColors()) { labels, colors ->
        labels.map { ColoredLabel(label = it.label, colorArgb = colors[it.label] ?: LabelPalette.default(it.label)) }
    }.flowOn(defaultDispatcher)
    fun getAppRefsFlow(): Flow<List<LabelAppCrossRefEntity>> = labelDao.queryAppRefsFlow()
    fun getFileRefsFlow(): Flow<List<LabelFileCrossRefEntity>> = labelDao.queryFileRefsFlow()
    suspend fun getLabels(): List<LabelEntity> = labelDao.queryLabels()
    suspend fun getLabelColors(): Map<String, Long> = context.readLabelColors().first()
    suspend fun getAppRefs(): List<LabelAppCrossRefEntity> = labelDao.queryAppRefs()
    suspend fun getFileRefs(): List<LabelFileCrossRefEntity> = labelDao.queryFileRefs()

    suspend fun addLabel(label: String): Boolean {
        if (labelDao.insertLabel(LabelEntity(label)) == -1L) return false
        context.saveLabelColor(label, LabelPalette.colors.random())
        return true
    }

    suspend fun setLabelColor(label: String, colorArgb: Long) = context.saveLabelColor(label, colorArgb)

    suspend fun setLabelColors(labelColors: Map<String, Long>) = context.saveLabelColors(labelColors)

    suspend fun addLabels(items: List<LabelEntity>) = labelDao.upsertLabels(items)

    suspend fun addLabelAppCrossRef(item: LabelAppCrossRefEntity) = addLabelAppCrossRefs(listOf(item))

    suspend fun addLabelAppCrossRefs(items: List<LabelAppCrossRefEntity>) {
        require(items.all { PathUtil.isValidPackageName(it.packageName) && it.userId >= 0 })
        labelDao.upsertAppRefs(items)
    }

    suspend fun addLabelFileCrossRef(item: LabelFileCrossRefEntity) = labelDao.upsert(item)

    suspend fun addLabelFileCrossRefs(items: List<LabelFileCrossRefEntity>) = labelDao.upsertFileRefs(items)

    suspend fun deleteLabel(label: String) {
        database.withTransaction {
            labelDao.deleteCompletely(label)
            database.appBackupDao().deleteUninstalledAppsWithoutRevisions()
        }
        context.deleteLabelPreferences(label)
    }

    suspend fun updateLabel(oldLabel: String, newLabel: String, colorArgb: Long, allowMerge: Boolean): Boolean {
        require(newLabel.isNotBlank())
        val updated = if (oldLabel == newLabel) labelDao.hasLabel(oldLabel) else labelDao.rename(oldLabel, newLabel, allowMerge)
        if (updated) context.saveEditedLabel(oldLabel, newLabel, colorArgb)
        return updated
    }

    suspend fun deleteLabelAppCrossRef(item: LabelAppCrossRefEntity) = deleteLabelAppCrossRefs(listOf(item))

    suspend fun deleteLabelAppCrossRefs(items: List<LabelAppCrossRefEntity>) = database.withTransaction {
        items.forEach { labelDao.deleteAppRef(it.label, it.packageName, it.userId) }
        items.map { it.userId }.distinct().forEach { database.appBackupDao().deleteUninstalledAppsWithoutRevisions(it) }
    }

    suspend fun deleteLabelFileCrossRef(item: LabelFileCrossRefEntity) {
        labelDao.deleteFileRef(item)
    }

    suspend fun deleteOrphanedAppRefs() = labelDao.deleteOrphanedAppRefs()
}
