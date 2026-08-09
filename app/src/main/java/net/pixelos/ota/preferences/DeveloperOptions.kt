/*
 * SPDX-FileCopyrightText: 2026 PixelOS
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pixelos.ota.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.ui.Category
import kotlinx.coroutines.launch
import net.pixelos.ota.R
import net.pixelos.ota.data.UpdateEndpointProvider
import net.pixelos.ota.data.UserPreferencesRepository

private enum class UrlOverride {
    UPDATE_FEED,
    CHANGELOG,
    CERTIFIED_PROPS,
}

@Composable
internal fun DeveloperOptions(repository: UserPreferencesRepository, showCertifiedProps: Boolean) {
    val coroutineScope = rememberCoroutineScope()
    val updateFeedUrl by repository.updateFeedUrlOverrideFlow.collectAsStateWithLifecycle("")
    val changelogUrl by repository.changelogUrlOverrideFlow.collectAsStateWithLifecycle("")
    val certifiedPropsUrl by
        repository.certifiedPropsUrlOverrideFlow.collectAsStateWithLifecycle("")
    var activeOverride by remember { mutableStateOf<UrlOverride?>(null) }

    Category(title = stringResource(R.string.pref_category_developer_options)) {
        UrlOverridePreference(
            title = stringResource(R.string.developer_update_feed_url),
            value = updateFeedUrl,
            onClick = { activeOverride = UrlOverride.UPDATE_FEED },
        )
        UrlOverridePreference(
            title = stringResource(R.string.developer_changelog_url),
            value = changelogUrl,
            onClick = { activeOverride = UrlOverride.CHANGELOG },
        )
        if (showCertifiedProps) {
            UrlOverridePreference(
                title = stringResource(R.string.developer_certified_props_url),
                value = certifiedPropsUrl,
                onClick = { activeOverride = UrlOverride.CERTIFIED_PROPS },
            )
        }
    }

    activeOverride?.let { override ->
        val initialValue =
            when (override) {
                UrlOverride.UPDATE_FEED -> updateFeedUrl
                UrlOverride.CHANGELOG -> changelogUrl
                UrlOverride.CERTIFIED_PROPS -> certifiedPropsUrl
            }
        val title =
            when (override) {
                UrlOverride.UPDATE_FEED -> stringResource(R.string.developer_update_feed_url)
                UrlOverride.CHANGELOG -> stringResource(R.string.developer_changelog_url)
                UrlOverride.CERTIFIED_PROPS ->
                    stringResource(R.string.developer_certified_props_url)
            }

        UrlOverrideDialog(
            title = title,
            initialValue = initialValue,
            onDismiss = { activeOverride = null },
            onSave = { value ->
                activeOverride = null
                coroutineScope.launch {
                    when (override) {
                        UrlOverride.UPDATE_FEED -> repository.setUpdateFeedUrlOverride(value)
                        UrlOverride.CHANGELOG -> repository.setChangelogUrlOverride(value)
                        UrlOverride.CERTIFIED_PROPS ->
                            repository.setCertifiedPropsUrlOverride(value)
                    }
                }
            },
        )
    }
}

@Composable
private fun UrlOverridePreference(title: String, value: String, onClick: () -> Unit) {
    val defaultSummary = stringResource(R.string.developer_url_default_summary)
    Preference(object : PreferenceModel {
        override val title = title
        override val summary = { value.ifBlank { defaultSummary } }
        override val onClick: (() -> Unit)? = onClick
    })
}

@Composable
private fun UrlOverrideDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val isValid = UpdateEndpointProvider.isValidOverride(value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.developer_url_dialog_message))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    label = { Text(stringResource(R.string.developer_url_hint)) },
                )
                if (!isValid) {
                    Text(
                        text = stringResource(R.string.developer_url_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = isValid) {
                Text(
                    stringResource(
                        if (value.isBlank()) {
                            R.string.developer_url_use_default
                        } else {
                            R.string.developer_url_save
                        }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
