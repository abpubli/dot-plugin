package io.github.abpubli.dotsupport.upgrade

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Service for checking Dot Plus plugin availability in JetBrains Marketplace.
 * 
 * Uses search page HTML parsing to extract JSON-LD structured data,
 * which is available even without JavaScript rendering.
 */
object MarketplaceChecker {

    private val LOG = Logger.getInstance(MarketplaceChecker::class.java)

    // Dot Plus plugin ID (textual)
    private const val DOT_PLUS_PLUGIN_ID = "org.abpubli.dot"

    // Search URL template
    private const val SEARCH_URL = "https://plugins.jetbrains.com/search?excludeTags=internal&search="

    // Plugin page URL pattern to extract numeric ID
    private val PLUGIN_URL_PATTERN = Pattern.compile("https://plugins\\.jetbrains\\.com/plugin/(\\d+)-([\\w-]+)")

    // JSON-LD pattern in HTML
    private val JSON_LD_PATTERN = Pattern.compile(
        "<script type=\"application/ld\\+json\">(\\{.*?\"@type\":\"ItemList\".*?\\})</script>",
        Pattern.DOTALL
    )

    data class PluginUpdateInfo(
        val version: String?,
        val isAvailable: Boolean,
        val isCompatible: Boolean,
        val pluginUrl: String?,
        val pluginName: String?
    )

    // JSON-LD structures
    data class ItemList(
        @SerializedName("@type") val type: String?,
        @SerializedName("itemListElement") val items: List<SoftwareApplication>?
    )

    data class SoftwareApplication(
        @SerializedName("@type") val type: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("url") val url: String?,
        @SerializedName("description") val description: String?
    )

    // Plugin details from API (after we have numeric ID)
    data class PluginDetails(
        @SerializedName("id") val id: Int?,
        @SerializedName("name") val name: String?,
        @SerializedName("version") val version: String?
    )

    data class PluginUpdate(
        @SerializedName("version") val version: String?,
        @SerializedName("id") val id: Int?
    )

    /**
     * Check if Dot Plus is available and compatible with current IDE
     */
    fun checkDotPlusAvailability(): PluginUpdateInfo {
        return try {
            // Step 1: Search for plugin by ID
            val searchResult = searchPlugin(DOT_PLUS_PLUGIN_ID)
            
            if (searchResult == null) {
                LOG.info("Dot Plus not found in Marketplace search")
                return PluginUpdateInfo(null, false, false, null, null)
            }

            LOG.info("Found plugin: ${searchResult.name} at ${searchResult.url}")

            // Step 2: Extract numeric ID from URL
            val numericId = extractNumericId(searchResult.url)
            if (numericId == null) {
                LOG.warn("Could not extract numeric ID from URL: ${searchResult.url}")
                return PluginUpdateInfo(null, true, false, searchResult.url, searchResult.name)
            }

            // Step 3: Check for compatible version using API
            val compatibleVersion = fetchCompatibleVersion(numericId)

            PluginUpdateInfo(
                version = compatibleVersion,
                isAvailable = true,
                isCompatible = compatibleVersion != null,
                pluginUrl = searchResult.url,
                pluginName = searchResult.name
            )

        } catch (e: Exception) {
            LOG.warn("Failed to check Dot Plus availability", e)
            PluginUpdateInfo(null, false, false, null, null)
        }
    }

    /**
     * Search for plugin by ID using Marketplace search page
     */
    private fun searchPlugin(pluginId: String): SoftwareApplication? {
        val encodedId = URLEncoder.encode(pluginId, "UTF-8")
        val url = URL("$SEARCH_URL$encodedId")

        LOG.info("Searching: $url")

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/html")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; DotSupport Plugin)")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        return try {
            if (connection.responseCode == 200) {
                val html = connection.inputStream.bufferedReader().readText()
                parseJsonLd(html)
            } else {
                LOG.warn("Search returned status: ${connection.responseCode}")
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse JSON-LD from HTML to find plugin info
     */
    private fun parseJsonLd(html: String): SoftwareApplication? {
        val matcher = JSON_LD_PATTERN.matcher(html)

        if (!matcher.find()) {
            LOG.info("No JSON-LD found in search results (plugin probably not found)")
            return null
        }

        val jsonLd = matcher.group(1)
        LOG.debug("Found JSON-LD: $jsonLd")

        return try {
            val itemList = Gson().fromJson(jsonLd, ItemList::class.java)
            // Return first result (most relevant)
            itemList.items?.firstOrNull()
        } catch (e: Exception) {
            LOG.warn("Failed to parse JSON-LD", e)
            null
        }
    }

    /**
     * Extract numeric plugin ID from URL like:
     * https://plugins.jetbrains.com/plugin/16595-bison-language-support -> 16595
     */
    private fun extractNumericId(url: String?): String? {
        if (url == null) return null
        val matcher = PLUGIN_URL_PATTERN.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    /**
     * Fetch compatible version for current IDE build
     */
    private fun fetchCompatibleVersion(numericId: String): String? {
        val buildNumber = com.intellij.openapi.application.ApplicationInfo.getInstance().build.asString()
        val url = URL("https://plugins.jetbrains.com/api/plugins/$numericId/updates?build=$buildNumber")

        LOG.info("Checking compatibility: $url")

        val connection = url.openConnection() as HttpURLConnection
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
