package com.pause.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.pause.app.di.IoDispatcher
import com.pause.app.domain.model.InstalledApp
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads launchable apps from PackageManager exactly once per refresh and keeps
 * a decoded icon cache. PackageManager calls are moved to an IO dispatcher.
 */
@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val packageManager: PackageManager = context.packageManager
    private val refreshMutex = Mutex()
    private val labelCache = ConcurrentHashMap<String, String>()

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    /**
     * Loads every launchable app, excluding Pause itself and launchers
     * (launchers are ignored by the accessibility service by design).
     *
     * System apps are included on purpose: preinstalled apps such as YouTube
     * or Chrome are exactly the kind of app users want a pause before.
     */
    suspend fun refresh() = refreshMutex.withLock {
        // PackageManager queries and icon decoding are expensive; never do them
        // on the caller's dispatcher (which is Main for both ViewModels).
        withContext(ioDispatcher) {
            val launcherPackages = packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0,
            ).filter { it.priority >= 0 }
                .mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }

            val launchable = packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            )

            val resolved = launchable.asSequence()
                .mapNotNull { resolveInfo -> resolveInfo.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .filter { info ->
                    info.packageName != context.packageName && info.packageName !in launcherPackages
                }
                .map { info ->
                    val label = runCatching {
                        packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(info.packageName)
                    labelCache[info.packageName] = label

                    val icon = runCatching {
                        packageManager.getApplicationIcon(info)
                            .toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
                            .asImageBitmap()
                    }.getOrNull()

                    InstalledApp(
                        packageName = info.packageName,
                        label = label,
                        icon = icon,
                    )
                }
                .sortedBy { it.label.lowercase(Locale.getDefault()) }
                .toList()

            _apps.value = resolved
        }
    }

    /**
     * Synchronous label lookup used by the accessibility service. Results are
     * cached, so only the first call for an app touches PackageManager.
     */
    fun labelFor(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val label = runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        labelCache[packageName] = label
        return label
    }

    /** Icon from the in-memory catalog, if it has been loaded in this process. */
    fun iconFor(packageName: String): ImageBitmap? =
        _apps.value.firstOrNull { it.packageName == packageName }?.icon

    /** Direct icon load for deep links and the pause surface. */
    suspend fun loadIconFor(packageName: String): ImageBitmap? = withContext(ioDispatcher) {
        runCatching {
            packageManager.getApplicationIcon(packageName)
                .toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
                .asImageBitmap()
        }.getOrNull()
    }

    /** Launch intent for the target app, or null if it was uninstalled. */
    fun launchIntentFor(packageName: String): Intent? =
        packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

    private companion object {
        const val ICON_SIZE_PX = 128
    }
}
