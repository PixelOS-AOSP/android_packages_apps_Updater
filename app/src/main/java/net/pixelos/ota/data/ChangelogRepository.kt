/*
 * SPDX-FileCopyrightText: 2026 PixelOS
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pixelos.ota.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ChangelogState {
    data object Idle : ChangelogState
    data object Loading : ChangelogState
    data class Loaded(val markdown: String) : ChangelogState
    data object Error : ChangelogState
}

class ChangelogRepository(
    private val updateEndpointProvider: UpdateEndpointProvider,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedMarkdown: String? = null

    suspend fun fetchChangelog(): String = withContext(Dispatchers.IO) {
        val url = updateEndpointProvider.changelogUrl()
        if (cachedKey == url) {
            cachedMarkdown?.let { return@withContext it }
        }

        val markdown = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected changelog HTTP status: ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty changelog response")
            val contentLength = body.contentLength()
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw IOException("Changelog is too large: $contentLength bytes")
            }
            val bytes = body.source().readByteArray(MAX_RESPONSE_BYTES + 1)
            if (bytes.size > MAX_RESPONSE_BYTES) {
                throw IOException("Changelog exceeds $MAX_RESPONSE_BYTES bytes")
            }
            bytes.decodeToString().trim()
        }

        cachedKey = url
        cachedMarkdown = markdown
        markdown
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256L * 1024L
    }
}
