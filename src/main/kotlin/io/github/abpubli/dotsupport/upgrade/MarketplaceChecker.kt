package io.github.abpubli.dotsupport.upgrade

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Service for checking Dot Plus plugin availability in JetBrains Marketplace.
 *
 * Uses official JetBrains Marketplace API endpoints.
 */
object MarketplaceChecker {

    private val LOG = Logger.getInstance(MarketplaceChecker::class.java)

    // Dot Plus plugin ID (textual)
    private const val DOT_PLUS_PLUGIN_ID = "org.abpubli.dot"

    // Official Marketplace API
    private const val SEARCH_API = "https://plugins.jetbrains.com/api/searchPlugins"
    private const val UPDATES_API = "https://plugins.jetbrains.com/api/plugins"

    data class PluginUpdateInfo(
        val version: String?,
        val isAvailable: Boolean,
        val isCompatible: Boolean,
        val pluginUrl: String?,
        val pluginName: String?
    )

    // Search API response
    data class SearchResponse(
        @SerializedName("plugins") val plugins: List<PluginSearchResult>?
    )

    data class PluginSearchResult(
        @SerializedName("id") val id: Int?,
        @SerializedName("xmlId") val xmlId: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("link") val link: String?,
        @SerializedName("version") val version: String?
    )

    // Updates API response
    data class PluginUpdate(
        @SerializedName("version") val version: String?,
        @SerializedName("id") val id: Int?
    )

    /**
     * Check if Dot Plus is available and compatible with current IDE
     */
    fun checkDotPlusAvailability(): PluginUpdateInfo {
        return try {
            // Step 1: Search for plugin by xmlId
            val searchResult = searchPlugin(DOT_PLUS_PLUGIN_ID)

            if (searchResult == null) {
                LOG.info("Dot Plus not found in Marketplace")
                return PluginUpdateInfo(null, false, false, null, null)
            }

            LOG.info("Found plugin: ${searchResult.name} (id=${searchResult.id})")

            val pluginUrl = searchResult.link?.let { "https://plugins.jetbrains.com$it" }
                ?: "https://plugins.jetbrains.com/plugin/${searchResult.id}"

            // Step 2: Check for compatible version
            val numericId = searchResult.id
            if (numericId == null) {
                LOG.warn("Plugin found but no numeric ID")
                return PluginUpdateInfo(
                    version = searchResult.version,
                    isAvailable = true,
                    isCompatible = false,
                    pluginUrl = pluginUrl,
                    pluginName = searchResult.name
                )
            }

            val compatibleVersion = fetchCompatibleVersion(numericId)

            PluginUpdateInfo(
                version = compatibleVersion ?: searchResult.version,
                isAvailable = true,
                isCompatible = compatibleVersion != null,
                pluginUrl = pluginUrl,
                pluginName = searchResult.name
            )

        } catch (e: Exception) {
            LOG.warn("Failed to check Dot Plus availability", e)
            PluginUpdateInfo(null, false, false, null, null)
        }
    }

    /**
     * Search for plugin using official API
     */
    private fun searchPlugin(pluginXmlId: String): PluginSearchResult? {
        val encodedId = URLEncoder.encode(pluginXmlId, Charsets.UTF_8)
        val uri = URI.create("$SEARCH_API?search=$encodedId&max=5")

        LOG.info("Searching: $uri")

        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        return try {
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                LOG.debug("Search response: $response")

                val searchResponse = Gson().fromJson(response, SearchResponse::class.java)

                // Find exact match by xmlId
                searchResponse.plugins?.find { it.xmlId == pluginXmlId }
                    ?: searchResponse.plugins?.firstOrNull()
            } else {
                LOG.warn("Search API returned status: ${connection.responseCode}")
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fetch compatible version for current IDE build
     */
    private fun fetchCompatibleVersion(numericId: Int): String? {
        val buildNumber = ApplicationInfo.getInstance().build.asString()
        val uri = URI.create("$UPDATES_API/$numericId/updates?build=$buildNumber")

        LOG.info("Checking compatibility: $uri")

        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        return try {
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val updates = Gson().fromJson(response, Array<PluginUpdate>::class.java)
                updates.firstOrNull()?.version
            } else {
                LOG.warn("Updates API returned status: ${connection.responseCode}")
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Check if Dot Plus is already installed
     */
    fun isDotPlusInstalled(): Boolean {
        return PluginManagerCore.getPlugin(PluginId.getId(DOT_PLUS_PLUGIN_ID)) != null
    }

    /**
     * Get Marketplace URL for Dot Plus
     */
    fun getMarketplaceUrl(): String {
        return "https://plugins.jetbrains.com/search?search=$DOT_PLUS_PLUGIN_ID"
    }
}
