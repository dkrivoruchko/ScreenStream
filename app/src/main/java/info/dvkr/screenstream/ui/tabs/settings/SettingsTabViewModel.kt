package info.dvkr.screenstream.ui.tabs.settings

import android.content.Context
import android.content.res.Resources
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModuleManager
import info.dvkr.screenstream.ui.tabs.settings.app.AppModuleSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
internal class SettingsTabViewModel(
    appModuleSettings: AppModuleSettings,
    streamingModulesManager: StreamingModuleManager,
    context: Context
) : ViewModel() {

    private val settingsListFull: List<ModuleSettings> =
        listOf(appModuleSettings).plus(streamingModulesManager.modules.map { it.moduleSettings })

    private val resources: Resources = ContextCompat.getContextForLanguage(context).resources

    private val _searchTextFlow: MutableStateFlow<String> = MutableStateFlow("")
    internal val searchTextFlow: StateFlow<String> = _searchTextFlow.asStateFlow()

    internal fun setSearchText(text: String) {
        _searchTextFlow.value = text
    }

    private val _filteredSettings: MutableStateFlow<List<ModuleSettings>> = MutableStateFlow(settingsListFull)
    internal val settingsListFlow: StateFlow<List<ModuleSettings>> = _filteredSettings.asStateFlow()

    init {
        XLog.d(getLog("init"))

        searchTextFlow.map { searchText ->
            when {
                searchText.isBlank() -> settingsListFull
                else -> settingsListFull.mapNotNull { module -> module.filterBy(resources, searchText) }
            }
        }
            .onEach { filteredSettings -> _filteredSettings.value = filteredSettings }
            .launchIn(viewModelScope)
    }

    internal fun getModuleSettingsItem(settingId: ModuleSettings.Id?): ModuleSettings.Item? =
        if (settingId == null) null
        else settingsListFull.firstOrNull { it.id == settingId.moduleId }?.groups
            ?.firstOrNull { it.id == settingId.groupId }?.items
            ?.firstOrNull { it.id == settingId.itemId }
}