package com.xayah.feature.main.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.datastore.DashboardFilterPreference
import com.xayah.core.data.repository.LabelsRepo
import com.xayah.core.data.repository.ListData
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.datastore.LabelFilterMode
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.ColoredLabel
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.util.of
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.launchOnDefault
import com.xayah.feature.main.list.ListBottomSheetUiState.Loading
import com.xayah.feature.main.list.ListBottomSheetUiState.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ListBottomSheetViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listDataRepo: ListDataRepo,
    private val appsRepo: AppsRepo,
    private val labelsRepo: LabelsRepo
) : ViewModel() {
    private val target: Target = savedStateHandle.get<String>(MainRoutes.ARG_TARGET)
        ?.let { Target.valueOf(it) }
        ?: Target.Apps
    private val opType: OpType = OpType.of(savedStateHandle.get<String>(MainRoutes.ARG_OP_TYPE))

    val uiState: StateFlow<ListBottomSheetUiState> = when (target) {
        Target.Apps -> combine(
            listDataRepo.getListData(),
            labelsRepo.getColoredLabelsFlow(),
        ) { lData, labels ->
            val listData = lData.castTo<ListData.Apps>()
            Success.Apps(
                opType = opType,
                showFilterSheet = listData.showFilterSheet,
                labelEntities = labels,
                labelFilters = listData.labelFilters,
                showDataItemsSheet = listData.showDataItemsSheet,
                filters = listData.filters,
                loadSystemApps = listData.scanScope.loadSystemApps,
            )
        }

        Target.Files -> combine(
            listDataRepo.getListData(),
            labelsRepo.getColoredLabelsFlow()
        ) { lData, labels ->
            val listData = lData.castTo<ListData.Files>()
            Success.Files(
                opType = opType,
                showFilterSheet = listData.showFilterSheet,
                labelEntities = labels,
                labelFilters = listData.labelFilters,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        initialValue = Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    fun setShowFilterSheet(value: Boolean) {
        viewModelScope.launchOnDefault {
            listDataRepo.setShowFilterSheet(value)
        }
    }

    fun setShowDataItemsSheet(value: Boolean) {
        viewModelScope.launchOnDefault {
            listDataRepo.setShowDataItemsSheet(value)
        }
    }

    fun setFilters(filters: DashboardFilterPreference) {
        viewModelScope.launchOnDefault { listDataRepo.setFilters(filters) }
    }

    fun cycleLabelFilter(label: String) {
        viewModelScope.launchOnDefault {
            listDataRepo.cycleLabelFilter(label)
        }
    }

    fun resetLabelFilter(label: String) {
        viewModelScope.launchOnDefault {
            listDataRepo.removeLabelFilter(label)
        }
    }

    fun resetFilters() {
        viewModelScope.launchOnDefault {
            listDataRepo.setFilters(DashboardFilterPreference(), resetLabels = true)
        }
    }

    fun setDataItems(selections: PackageDataStates) {
        viewModelScope.launchOnDefault {
            appsRepo.setDataItems(listDataRepo.getSelectedAppIds(), selections)
        }
    }
}

sealed interface ListBottomSheetUiState {
    data object Loading : ListBottomSheetUiState
    sealed class Success(
        open val opType: OpType,
        open val showFilterSheet: Boolean,
        open val labelEntities: List<ColoredLabel>,
        open val labelFilters: Map<String, LabelFilterMode>,
    ) : ListBottomSheetUiState {
        data class Apps(
            override val opType: OpType,
            override val showFilterSheet: Boolean,
            override val labelEntities: List<ColoredLabel>,
            override val labelFilters: Map<String, LabelFilterMode>,
            val showDataItemsSheet: Boolean,
            val filters: DashboardFilterPreference,
            val loadSystemApps: Boolean,
        ) : Success(opType, showFilterSheet, labelEntities, labelFilters)

        data class Files(
            override val opType: OpType,
            override val showFilterSheet: Boolean,
            override val labelEntities: List<ColoredLabel>,
            override val labelFilters: Map<String, LabelFilterMode>,
        ) : Success(opType, showFilterSheet, labelEntities, labelFilters)
    }
}
