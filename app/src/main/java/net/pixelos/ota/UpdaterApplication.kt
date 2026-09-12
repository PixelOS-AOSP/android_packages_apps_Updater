/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pixelos.ota

import android.app.Application
import android.util.Log
import com.android.settingslib.spa.framework.common.SettingsPageProviderRepository
import com.android.settingslib.spa.framework.common.SpaEnvironment
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.pixelos.ota.certifiedprops.CertifiedPropsRepository
import net.pixelos.ota.data.AppStateRepository
import net.pixelos.ota.data.ChangelogRepository
import net.pixelos.ota.data.UpdatesRepository
import net.pixelos.ota.data.UserPreferencesRepository
import net.pixelos.ota.data.source.local.UpdatesDatabase
import net.pixelos.ota.data.source.local.UpdatesLocalDataSource
import net.pixelos.ota.data.source.network.UpdatesNetworkDataSource
import net.pixelos.ota.deviceinfo.DeviceInfoUtils
import net.pixelos.ota.notifications.NotificationHelper
import net.pixelos.ota.util.BatteryMonitor
import net.pixelos.ota.util.NetworkMonitor

private const val TAG = "UpdaterApplication"

class UpdaterApplication : Application() {
    private val coroutineScope = MainScope()
    private var updatesResyncJob: Job? = null
    private val database by lazy { UpdatesDatabase.getInstance(applicationContext) }
    private val networkDataSource by lazy { UpdatesNetworkDataSource(applicationContext) }
    private val localDataSource by lazy { UpdatesLocalDataSource(database.updateDao()) }


    val batteryMonitor by lazy {
        BatteryMonitor(applicationContext, coroutineScope, userPreferencesRepository)
    }
    val networkMonitor by lazy { NetworkMonitor(applicationContext, coroutineScope) }
    val notificationHelper by lazy { NotificationHelper(applicationContext) }
    val appStateRepository by lazy { AppStateRepository(applicationContext) }
    val changelogRepository by lazy { ChangelogRepository(applicationContext) }
    val certifiedPropsRepository by lazy { CertifiedPropsRepository(applicationContext) }
    val userPreferencesRepository by lazy { UserPreferencesRepository(applicationContext) }
    val updatesRepository by lazy {
        UpdatesRepository(
            context = applicationContext,
            networkMonitor = networkMonitor,
            notificationHelper = notificationHelper,
            networkDataSource = networkDataSource,
            localDataSource = localDataSource,
            userPreferencesRepository = userPreferencesRepository,
        )
    }

    /**
     * Re-syncs the update list from the feed. Used when the stored updates no longer
     * describe what the server offers, such as an incremental left behind after its full
     * package was cancelled. Fire-and-forget, and a no-op while a sync is already running.
     */
    fun requestUpdatesResync() {
        if (updatesResyncJob?.isActive == true) {
            return
        }
        updatesResyncJob = coroutineScope.launch {
            try {
                updatesRepository.fetchUpdates()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-sync updates", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DeviceInfoUtils.initialize(applicationContext)
        notificationHelper.setUpNotificationChannels()
        coroutineScope.launch {
            userPreferencesRepository.migrateLegacyPreferences()
        }
        coroutineScope.launch {
            // The build only changes across a reboot, so this is the moment an update
            // stops being an update and becomes the system the user is running.
            updatesRepository.pruneInstalledUpdates()
        }
        SpaEnvironmentFactory.reset(object : SpaEnvironment(applicationContext) {
            override val pageProviderRepository = lazy {
                SettingsPageProviderRepository(emptyList())
            }

            override val isSpaExpressiveEnabled = true
        })
    }
}
