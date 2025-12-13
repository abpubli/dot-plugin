package io.github.abpubli.dotsupport.upgrade

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service for checking Dot Plus plugin availability in JetBrains Marketplace
 */
object MarketplaceChecker {

    private val LOG = Logger.getInstance(MarketplaceChecker::class.java)

    // Dot Plus plugin ID in Marketplace
    private const val DOT_PLUS_PLUGIN_ID = "org.abpubli.dot"

    // API endpoint - we use updates endpoint which returns IDE-compatible versions
    private const val MARKETPLACE_API = "https://plugins.jetbrains.com/api/plugins"

    data class PluginUpdateInfo(
        val version: String?,
        val isAvailable: Boolean,
        val isCompatible: Boolean,
        val downloadUrl: String?
    )

    /**
     * Response from Marketplace API for a single update
     */
    data class PluginUpdate(
        @SerializedName("version") val version: String?,
        @SerializedName("compatibleVersions") val compatibleVersions: CompatibleVersions?,
        @SerializedName("file") val file: String?
    )

    data class CompatibleVersions(
        @SerializedName("IDEA") val idea: List<String>?
    )

    /**
     * Main plugin information
     */
    data class PluginInfo(
        @SerializedName("id") val id: Int?,
        @SerializedName("name") val name: String?,
        @SerializedName("link") val link: String?
    )

    /**
     * Checks if Dot Plus is available and compatible with current IDE
     */
    fun checkDotPlusAvailability(): PluginUpdateInfo {
        return try {
            // First check if plugin exists at all
            val pluginInfo = fetchPluginInfo()
            if (pluginInfo == null) {
                LOG.info("Dot Plus plugin not found in marketplace")
                return PluginUpdateInfo(null, false, false, null)
            }

            // Check compatible version
            val compatibleUpdate = fetchCompatibleUpdate()
            if (compatibleUpdate != null) {
                LOG.info("Dot Plus available: version ${compatibleUpdate.version}")
                PluginUpdateInfo(
                    version = compatibleUpdate.version,
                    isAvailable = true,
                    isCompatible = true,
                    downloadUrl = "https://plugins.jetbrains.com/plugin/${pluginInfo.id}"
                )
            } else {
                LOG.info("Dot Plus exists but no compatible version for current IDE")
                PluginUpdateInfo(
                    version = null,
                    isAvailable = true,
                    isCompatible = false,
                    downloadUrl = "https://plugins.jetbrains.com/plugin/${pluginInfo.id}"
                )
            }
        } catch (e: Exception) {
            LOG.warn("Failed to check Dot Plus availability", e)
            PluginUpdateInfo(null, false, false, null)
        }
    }

    /**
     * Fetches plugin information
     */
    private fun fetchPluginInfo(): PluginInfo? {
        val url = URL("$MARKETPLACE_API/$DOT_PLUS_PLUGIN_ID")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        return try {
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                Gson().fromJson(response, PluginInfo::class.java)
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fetches compatible update for current IDE
     */
    private fun fetchCompatibleUpdate(): PluginUpdate? {
        val buildNumber = ApplicationInfo.getInstance().build.asString()
        val url = URL("$MARKETPLACE_API/$DOT_PLUS_PLUGIN_ID/updates?build=$buildNumber")
        
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        return try {
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val updates = Gson().fromJson(response, Array<PluginUpdate>::class.java)
                updates.firstOrNull()
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Checks if Dot Plus is already installed
     */
    fun isDotPlusInstalled(): Boolean {
        return PluginManagerCore.getPlugin(PluginId.getId(DOT_PLUS_PLUGIN_ID)) != null
    }

    /**
     * Returns URL to plugin page in Marketplace
     */
    fun getMarketplaceUrl(): String {
        return "https://plugins.jetbrains.com/plugin/$DOT_PLUS_PLUGIN_ID"
    }
}
