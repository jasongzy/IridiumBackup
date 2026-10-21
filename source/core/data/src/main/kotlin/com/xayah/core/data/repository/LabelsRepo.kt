package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.database.dao.LabelDao
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.datastore.readLabelColors
import com.xayah.core.datastore.saveLabelColor
import com.xayah.core.datastore.saveLabelColors
import com.xayah.core.datastore.deleteLabelColor
import com.xayah.core.datastore.renameLabelColor
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
    private val labelDao: LabelDao,
) {
    fun getLabelsFlow(): Flow<List<LabelEntity>> = labelDao.queryLabelsFlow().flowOn(defaultDispatcher)
    fun getColoredLabelsFlow(): Flow<List<ColoredLabel>> = combine(labelDao.queryLabelsFlow(), context.readLabelColors()) { labels, colors ->
        labels.map { ColoredLabel(label = it.label, colorArgb = colors[it.label] ?: LabelPalette.default(it.label)) }
    }.flowOn(defaultDispatcher)
    fun getAppRefsFlow(): Flow<List<LabelAppCrossRefEntity>> = labelDao.queryAppRefsFlow()
    fun getFileRefsFlow(): Flow<List<LabelFileCrossRefEntity>> = labelDao.queryFileRefsFlow()
    suspend fun getLabels(): List<LabelEntity> = labelDao.queryLabels()
    suspend fun getLabelColors(): Map<String, Long> = context.readLabelColors().first()
    suspend fun getAppRefs(labelIds: Set<String>): List<LabelAppCrossRefEntity> = labelDao.queryAppRefs(labelIds)
    suspend fun getAppRefs(): List<LabelAppCrossRefEntity> = labelDao.queryAppRefs()
    suspend fun getFileRefs(labelIds: Set<String>): List<LabelFileCrossRefEntity> = labelDao.queryFileRefs(labelIds)
    suspend fun getFileRefs(): List<LabelFileCrossRefEntity> = labelDao.queryFileRefs()

    /**
     * Add a unique label
     */
    suspend fun addLabel(label: String) {
        val exists = getLabels().any { it.label == label }
        labelDao.upsert(LabelEntity(label = label))
        if (!exists) {
            context.saveLabelColor(label, LabelPalette.colors.random())
        }
    }

    suspend fun setLabelColor(label: String, colorArgb: Long) = context.saveLabelColor(label, colorArgb)

    suspend fun setLabelColors(labelColors: Map<String, Long>) = context.saveLabelColors(labelColors)

    suspend fun addLabels(items: List<LabelEntity>) = labelDao.upsertLabels(items)

    /**
     * Add a unique label app cross ref
     */
    suspend fun addLabelAppCrossRef(item: LabelAppCrossRefEntity) = addLabelAppCrossRefs(listOf(item))

    suspend fun addLabelAppCrossRefs(items: List<LabelAppCrossRefEntity>) {
        require(items.all { PathUtil.isValidPackageName(it.packageName) && it.userId >= 0 && it.preserveId >= 0 })
        labelDao.upsertAppRefs(items)
    }

    /**
     * Add a unique label file cross ref
     */
    suspend fun addLabelFileCrossRef(item: LabelFileCrossRefEntity) = labelDao.upsert(item)

    suspend fun addLabelFileCrossRefs(items: List<LabelFileCrossRefEntity>) = labelDao.upsertFileRefs(items)


    suspend fun deleteLabel(label: String) {
        labelDao.deleteCompletely(label)
        context.deleteLabelColor(label)
    }

    suspend fun renameLabel(oldLabel: String, newLabel: String) {
        val normalized = newLabel.trim()
        require(normalized.isNotEmpty())
        labelDao.rename(oldLabel, normalized)
        context.renameLabelColor(oldLabel, normalized)
    }

    suspend fun deleteLabelAppCrossRef(item: LabelAppCrossRefEntity) {
        labelDao.deleteAppRef(item)
    }

    suspend fun deleteLabelAppCrossRefs(items: List<LabelAppCrossRefEntity>) = labelDao.deleteAppRefs(items)

    suspend fun deleteLabelFileCrossRef(item: LabelFileCrossRefEntity) {
        labelDao.deleteFileRef(item)
    }

    suspend fun deleteOrphanedAppRefs() = labelDao.deleteOrphanedAppRefs()
}
