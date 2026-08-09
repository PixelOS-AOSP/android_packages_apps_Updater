/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package net.pixelos.ota.data.source.network

import kotlinx.serialization.json.Json
import net.pixelos.ota.data.UpdateEndpointProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdatesNetworkDataSource(
    private val updateEndpointProvider: UpdateEndpointProvider,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun fetchUpdates(): List<NetworkUpdate> {
        val request = Request.Builder()
            .url(updateEndpointProvider.updateFeedUrl())
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP status: ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val contentLength = body.contentLength()
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw IOException("Update response is too large: $contentLength bytes")
            }
            val source = body.source()
            val hasMore = source.request(MAX_RESPONSE_BYTES + 1)
            if (hasMore) {
                throw IOException("Update response exceeds $MAX_RESPONSE_BYTES bytes")
            }
            source.buffer.readByteArray().decodeToString()
        }

        return Json.decodeFromString<List<NetworkUpdate>>(responseBody).onEach { it.validate() }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024L * 1024L
    }
}
