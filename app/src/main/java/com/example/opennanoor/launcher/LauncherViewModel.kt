package com.example.opennanoor.launcher

import android.app.Application
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opennanoor.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An app plus whatever icon we ended up showing for it. */
data class LauncherEntry(
    val app: LaunchableApp,
    val icon: Drawable
)

data class LauncherUiState(
    val entries: List<LauncherEntry> = emptyList(),
    val availablePacks: List<IconPackInfo> = emptyList(),
    val activePack: String? = null,
    val loading: Boolean = true
)

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val _state = MutableStateFlow(LauncherUiState())
    val state: StateFlow<LauncherUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val context = getApplication<Application>()
            val packChoice = settings.iconPackPackage

            val loaded = withContext(Dispatchers.IO) {
                val apps = AppRepository.installedApps(context)
                val packs = IconPack.installedPacks(context)
                val pack = packChoice?.let { IconPack.load(context, it) }

                val entries = apps.map { app ->
                    val themed = pack?.iconFor(app.component, app.rawIcon, ICON_PX)
                    LauncherEntry(app, themed ?: app.rawIcon)
                }
                Triple(entries, packs, pack != null)
            }

            _state.value = LauncherUiState(
                entries = loaded.first,
                availablePacks = loaded.second,
                activePack = packChoice.takeIf { loaded.third },
                loading = false
            )
        }
    }

    fun selectIconPack(packageName: String?) {
        settings.iconPackPackage = packageName
        refresh()
    }

    private companion object {
        /** Render size for composited icons. Generous so they stay sharp. */
        const val ICON_PX = 192
    }
}
