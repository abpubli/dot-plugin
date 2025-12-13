package io.github.abpubli.dotsupport.upgrade

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*

/**
 * Dot Plus notification settings panel.
 * Can be used as a separate configurable or added to the existing DotSettingsConfigurable.
 */
class DotPlusNotificationConfigurable : BoundConfigurable("Dot Plus Notifications") {

    private val settings = DotPlusNotificationSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("Dot Plus Upgrade Notifications") {
            row {
                label("Dot Plus is a premium edition with advanced features like syntax highlighting, code folding, and more.")
            }

            buttonsGroup("Notification frequency:") {
                row {
                    radioButton("Remind me daily", NotificationMode.DAILY)
                        .comment("Show notification once per day when Dot Plus is available")
                }
                row {
                    radioButton("Only when new version is released", NotificationMode.ON_NEW_VERSION)
                        .comment("Notify only when Dot Plus gets an update")
                }
                row {
                    radioButton("Don't show notifications", NotificationMode.NEVER)
                        .comment("Never show Dot Plus notifications")
                }
            }.bind(
                { settings.notificationMode },
                { settings.notificationMode = it }
            )

            row {
                button("Check Now") {
                    checkNow()
                }
                button("Reset") {
                    resetSettings()
                }
            }

            if (settings.lastCheckDate != null) {
                row {
                    comment("Last checked: ${settings.lastCheckDate}")
                }
            }

            if (settings.lastKnownVersion.isNotEmpty()) {
                row {
                    comment("Last known Dot Plus version: ${settings.lastKnownVersion}")
                }
            }
        }

        group("About Dot Plus") {
            row {
                browserLink("View in JetBrains Marketplace", MarketplaceChecker.getMarketplaceUrl())
            }
            row {
                label("Features: Syntax highlighting, Code folding, Find usages, Multiple engines, and more!")
            }
            row {
                comment("Free 30-day trial available. After trial, reverts to Dot Support functionality if not purchased.")
            }
        }
    }

    private fun checkNow() {
        val result = MarketplaceChecker.checkDotPlusAvailability()
        settings.recordCheck(result.version, result.isAvailable)

        val message = when {
            !result.isAvailable -> "Dot Plus is not yet available in the Marketplace."
            !result.isCompatible -> "Dot Plus is available but not compatible with your IDE version."
            else -> "Dot Plus v${result.version} is available and compatible with your IDE!"
        }

        com.intellij.openapi.ui.Messages.showInfoMessage(message, "Dot Plus Check")
    }

    private fun resetSettings() {
        settings.notificationMode = NotificationMode.DAILY
        settings.lastKnownVersion = ""
        settings.dotPlusWasAvailable = false
        // lastCheckDate remains to avoid immediate spam

        com.intellij.openapi.ui.Messages.showInfoMessage(
            "Notification settings have been reset to defaults.",
            "Settings Reset"
        )
    }
}
