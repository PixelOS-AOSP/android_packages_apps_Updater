/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pixelos.ota.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import net.pixelos.ota.deviceinfo.DeviceInfoUtils
import net.pixelos.ota.updatescheck.UpdatesCheckWorker
import java.io.IOException

private const val USER_PREFERENCES_DATASTORE_NAME = "user_prefs"

private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = USER_PREFERENCES_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler {
        emptyPreferences()
    },
)

private object UserPreferencesKeys {
    val AB_PERF_MODE = booleanPreferencesKey("ab_perf_mode")
    val AUTO_DELETE = booleanPreferencesKey("auto_delete_updates")
    val CHECK_INTERVAL = stringPreferencesKey("check_interval")
    val INCREMENTAL_UPDATES = booleanPreferencesKey("incremental_updates")
    val METERED_NETWORK_WARNING = booleanPreferencesKey("metered_network_warning")
    val PERIODIC_CHECK_ENABLED = booleanPreferencesKey("periodic_check_enabled")
    val STREAM_UPDATES = booleanPreferencesKey("stream_updates")
    val UPDATE_FEED_URL_OVERRIDE = stringPreferencesKey("update_feed_url_override")
    val CHANGELOG_URL_OVERRIDE = stringPreferencesKey("changelog_url_override")
    val CERTIFIED_PROPS_URL_OVERRIDE =
        stringPreferencesKey("certified_props_url_override")
    val LEGACY_PREFERENCES_MIGRATED = booleanPreferencesKey("legacy_preferences_migrated")
}

private object LegacyPreferenceKeys {
    const val AB_PERF_MODE = "ab_perf_mode"
    const val AUTO_DELETE = "auto_delete_updates"
    const val PERIODIC_CHECK_ENABLED = "auto_updates_check"
    const val STREAM_UPDATES = "stream_ota"
}

class UserPreferencesRepository(context: Context) {
    private val appContext = context.applicationContext
    private val userPreferences = appContext.userPreferencesDataStore
    private val userPreferencesFlow = userPreferences.data.catch { exception ->
        if (exception is IOException) emit(emptyPreferences()) else throw exception
    }

    suspend fun migrateLegacyPreferences() {
        val legacy = PreferenceManager.getDefaultSharedPreferences(appContext)
        var migrated = false
        userPreferences.edit { preferences ->
            if (preferences[UserPreferencesKeys.LEGACY_PREFERENCES_MIGRATED] == true) {
                return@edit
            }

            fun migrateBoolean(legacyKey: String, targetKey: Preferences.Key<Boolean>) {
                if (legacy.contains(legacyKey)) {
                    preferences[targetKey] = legacy.getBoolean(legacyKey, false)
                }
            }

            migrateBoolean(
                LegacyPreferenceKeys.AB_PERF_MODE,
                UserPreferencesKeys.AB_PERF_MODE,
            )
            migrateBoolean(
                LegacyPreferenceKeys.AUTO_DELETE,
                UserPreferencesKeys.AUTO_DELETE,
            )
            migrateBoolean(
                LegacyPreferenceKeys.PERIODIC_CHECK_ENABLED,
                UserPreferencesKeys.PERIODIC_CHECK_ENABLED,
            )
            migrateBoolean(
                LegacyPreferenceKeys.STREAM_UPDATES,
                UserPreferencesKeys.STREAM_UPDATES,
            )
            preferences[UserPreferencesKeys.LEGACY_PREFERENCES_MIGRATED] = true
            migrated = true
        }

        if (migrated) {
            UpdatesCheckWorker.reschedulePeriodicCheck(appContext)
        }
    }

    val abPerfModeFlow: Flow<Boolean> = userPreferencesFlow.map { preferences ->
        preferences[UserPreferencesKeys.AB_PERF_MODE] ?: true
    }

    suspend fun getAbPerfMode(): Boolean = abPerfModeFlow.first()

    fun getAbPerfModeBlocking(): Boolean = runBlocking { getAbPerfMode() }

    suspend fun setAbPerfMode(value: Boolean) {
        userPreferences.edit { it[UserPreferencesKeys.AB_PERF_MODE] = value }
    }

    val autoDeleteFlow: Flow<Boolean> = userPreferencesFlow.map { preferences ->
        preferences[UserPreferencesKeys.AUTO_DELETE] ?: true
    }

    suspend fun getAutoDelete(): Boolean = autoDeleteFlow.first()

    fun getAutoDeleteBlocking(): Boolean = runBlocking { getAutoDelete() }

    suspend fun setAutoDelete(value: Boolean) {
        userPreferences.edit { it[UserPreferencesKeys.AUTO_DELETE] = value }
    }

    val incrementalUpdatesFlow: Flow<Boolean> = userPreferencesFlow.map { preferences ->
        preferences[UserPreferencesKeys.INCREMENTAL_UPDATES] ?: true
    }

    suspend fun getIncrementalUpdates(): Boolean = incrementalUpdatesFlow.first()

    suspend fun setIncrementalUpdates(value: Boolean) {
        userPreferences.edit { it[UserPreferencesKeys.INCREMENTAL_UPDATES] = value }
    }

    val meteredNetworkWarningFlow: Flow<Boolean> = userPreferencesFlow.map { preferences ->
        preferences[UserPreferencesKeys.METERED_NETWORK_WARNING] ?: true
    }

    suspend fun getMeteredNetworkWarning(): Boolean = meteredNetworkWarningFlow.first()

    fun getMeteredNetworkWarningBlocking(): Boolean = runBlocking { getMeteredNetworkWarning() }

    suspend fun setMeteredNetworkWarning(value: Boolean) {
        userPreferences.edit { it[UserPreferencesKeys.METERED_NETWORK_WARNING] = value }
    }

    val checkIntervalFlow: Flow<CheckInterval> = userPreferencesFlow.map { preferences ->
        CheckInterval.fromStorageValue(preferences[UserPreferencesKeys.CHECK_INTERVAL])
    }

    suspend fun getCheckInterval(): CheckInterval = checkIntervalFlow.first()

    suspend fun setCheckInterval(interval: CheckInterval) {
        userPreferences.edit { it[UserPreferencesKeys.CHECK_INTERVAL] = interval.storageValue }
        if (getPeriodicCheckEnabled()) {
            UpdatesCheckWorker.reschedulePeriodicCheck(appContext)
        }
    }

    val periodicCheckEnabledFlow: Flow<Boolean> = userPreferencesFlow.map { preferences ->
        preferences[UserPreferencesKeys.PERIODIC_CHECK_ENABLED] ?: true
    }

    suspend fun getPeriodicCheckEnabled(): Boolean = periodicCheckEnabledFlow.first()

    suspend fun setPeriodicCheckEnabled(enabled: Boolean) {
        userPreferences.edit { it[UserPreferencesKeys.PERIODIC_CHECK_ENABLED] = enabled }
        if (enabled) {
            UpdatesCheckWorker.schedulePeriodicCheck(appContext)
        } else {
            UpdatesCheckWorker.cancelPeriodicCheck(appContext)
        }
    }

    val streamUpdatesFlow: Flow<Boolean> = userPreferencesFlow.map { preferences ->
        preferences[UserPreferencesKeys.STREAM_UPDATES] ?: true
    }

    suspend fun getStreamUpdates(): Boolean = streamUpdatesFlow.first()

    fun getStreamUpdatesBlocking(): Boolean = runBlocking { getStreamUpdates() }

    suspend fun setStreamUpdates(value: Boolean) {
        userPreferences.edit { it[UserPreferencesKeys.STREAM_UPDATES] = value }
    }

    val updateFeedUrlOverrideFlow: Flow<String> = userPreferencesFlow.map { preferences ->
        preferences[UserPreferencesKeys.UPDATE_FEED_URL_OVERRIDE].orEmpty()
    }

    suspend fun getUpdateFeedUrlOverride(): String = updateFeedUrlOverrideFlow.first()

    suspend fun setUpdateFeedUrlOverride(value: String) {
        setUrlOverride(UserPreferencesKeys.UPDATE_FEED_URL_OVERRIDE, value)
    }

    val changelogUrlOverrideFlow: Flow<String> = userPreferencesFlow.map { preferences ->
        preferences[UserPreferencesKeys.CHANGELOG_URL_OVERRIDE].orEmpty()
    }

    suspend fun getChangelogUrlOverride(): String = changelogUrlOverrideFlow.first()

    suspend fun setChangelogUrlOverride(value: String) {
        setUrlOverride(UserPreferencesKeys.CHANGELOG_URL_OVERRIDE, value)
    }

    val certifiedPropsUrlOverrideFlow: Flow<String> =
        userPreferencesFlow.map { preferences ->
            preferences[UserPreferencesKeys.CERTIFIED_PROPS_URL_OVERRIDE].orEmpty()
        }

    suspend fun getCertifiedPropsUrlOverride(): String =
        certifiedPropsUrlOverrideFlow.first()

    suspend fun setCertifiedPropsUrlOverride(value: String) {
        setUrlOverride(UserPreferencesKeys.CERTIFIED_PROPS_URL_OVERRIDE, value)
    }

    private suspend fun setUrlOverride(key: Preferences.Key<String>, value: String) {
        userPreferences.edit { preferences ->
            val normalized = value.trim()
            if (normalized.isEmpty()) {
                preferences.remove(key)
            } else {
                preferences[key] = normalized
            }
        }
    }

    fun getRecoveryUpdateEnabled(): Boolean = DeviceInfoUtils.isRecoveryUpdateEnabled

    fun setRecoveryUpdateEnabled(enabled: Boolean) {
        DeviceInfoUtils.isRecoveryUpdateEnabled = enabled
    }
}
