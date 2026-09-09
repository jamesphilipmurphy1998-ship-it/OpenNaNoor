package com.example.opennanoor.launcher

import android.app.Application
import android.content.ComponentName
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
    val pages: List<List<LauncherEntry>> = emptyList(),
    val dock: List<LauncherEntry> = emptyList(),
    val allApps: List<LauncherEntry> = emptyList(),
    val availablePacks: List<IconPackInfo> = emptyList(),
    val activePack: String? = null,
    val iosStyle: Boolean = false,
    val columns: Int = 4,
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
            val ios = settings.iosIconStyle
            val columns = settings.columns

            val result = withContext(Dispatchers.IO) {
                val apps = AppRepository.installedApps(context)
                val packs = IconPack.installedPacks(context)
                val pack = packChoice?.let { IconPack.load(context, it) }

                val byComponent = apps.associateBy { it.component }
                val icons = apps.associate { app ->
                    val themed = pack?.iconFor(app.component, app.rawIcon, ICON_PX)
                        ?: app.rawIcon
                    // The squircle runs last, so it shapes pack artwork too.
                    val finished = if (ios) SquircleIcons.apply(themed, ICON_PX) else themed
                    app.component to LauncherEntry(app, finished)
                }

                val layout = HomeLayout.load(context, byComponent.keys)
                    ?: HomeLayout.default(apps, columns).also {
                        HomeLayout.save(context, it)
                    }

                // Apps installed since the layout was written land on a new page
                // rather than silently disappearing.
                val placed = (layout.pages.flatten() + layout.dock).toSet()
                val unplaced = apps.map { it.component }.filterNot { it in placed }
                val pages = layout.pages + unplaced
                    .chunked(columns * HomeLayout.ROWS_PER_PAGE)
                    .filter { it.isNotEmpty() }

                Loaded(
                    pages = pages.map { page -> page.mapNotNull(icons::get) },
                    dock = layout.dock.mapNotNull(icons::get),
                    allApps = apps.mapNotNull { icons[it.component] },
                    packs = packs,
                    packActive = pack != null
                )
            }

            _state.value = LauncherUiState(
                pages = result.pages,
                dock = result.dock,
                allApps = result.allApps,
                availablePacks = result.packs,
                activePack = packChoice.takeIf { result.packActive },
                iosStyle = ios,
                columns = columns,
                loading = false
            )
        }
    }

    fun selectIconPack(packageName: String?) {
        settings.iconPackPackage = packageName
        refresh()
    }

    fun setIosStyle(enabled: Boolean) {
        settings.iosIconStyle = enabled
        refresh()
    }

    private data class Loaded(
        val pages: List<List<LauncherEntry>>,
        val dock: List<LauncherEntry>,
        val allApps: List<LauncherEntry>,
        val packs: List<IconPackInfo>,
        val packActive: Boolean
    )

    private companion object {
        /** Render size for icons. Generous so they stay sharp when scaled. */
        const val ICON_PX = 192
    }
}
