/*
 * SPDX-FileCopyrightText: 2026 PixelOS
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pixelos.ota.data

import android.content.Context
import androidx.annotation.StringRes
import net.pixelos.ota.R
import net.pixelos.ota.deviceinfo.DeviceInfoUtils
import java.net.URI

class UpdateEndpointProvider(
    context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    private val appContext = context.applicationContext

    suspend fun updateFeedUrl(): String =
        resolve(userPreferencesRepository.getUpdateFeedUrlOverride(), R.string.updater_server_url)

    suspend fun changelogUrl(): String =
        resolve(userPreferencesRepository.getChangelogUrlOverride(), R.string.changelog_url)

    suspend fun certifiedPropsUrl(): String =
        resolve(
            userPreferencesRepository.getCertifiedPropsUrlOverride(),
            R.string.certified_prop_url,
        )

    private fun resolve(override: String, @StringRes defaultUrlRes: Int): String {
        val template = override.ifBlank { appContext.getString(defaultUrlRes) }.trim()
        require(isValidOverride(template)) { "Invalid update endpoint URL" }

        require(!template.contains(BRANCH_PLACEHOLDER) || DeviceInfoUtils.otaBranch.isNotBlank()) {
            "Missing net.pixelos.version"
        }
        require(!template.contains(DEVICE_PLACEHOLDER) || DeviceInfoUtils.device.isNotBlank()) {
            "Missing ro.custom.device"
        }
        require(!template.contains(TYPE_PLACEHOLDER) || DeviceInfoUtils.buildType.isNotBlank()) {
            "Missing net.pixelos.build_type"
        }

        val resolved =
            template
                .replace(BRANCH_PLACEHOLDER, DeviceInfoUtils.otaBranch)
                .replace(DEVICE_PLACEHOLDER, DeviceInfoUtils.device)
                .replace(TYPE_PLACEHOLDER, DeviceInfoUtils.buildType)
        require(isValidResolvedUrl(resolved)) { "Invalid resolved update endpoint URL" }
        return resolved
    }

    companion object {
        private const val BRANCH_PLACEHOLDER = "{branch}"
        private const val DEVICE_PLACEHOLDER = "{device}"
        private const val TYPE_PLACEHOLDER = "{type}"

        fun isValidOverride(value: String): Boolean {
            val template = value.trim()
            if (template.isEmpty()) return true

            val resolved =
                template
                    .replace(BRANCH_PLACEHOLDER, "branch")
                    .replace(DEVICE_PLACEHOLDER, "device")
                    .replace(TYPE_PLACEHOLDER, "type")

            // Every supported placeholder is gone by now, so a leftover brace means the
            // template carries an unsupported or malformed one.
            if (resolved.any { it == '{' || it == '}' }) return false

            return isValidResolvedUrl(resolved)
        }

        private fun isValidResolvedUrl(value: String): Boolean =
            runCatching {
                    val uri = URI(value)
                    uri.scheme.equals("https", ignoreCase = true) &&
                        !uri.host.isNullOrBlank() &&
                        uri.userInfo == null
                }
                .getOrDefault(false)
    }
}
