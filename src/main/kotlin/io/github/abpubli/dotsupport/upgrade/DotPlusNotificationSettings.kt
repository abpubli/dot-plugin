package io.github.abpubli.dotsupport.upgrade

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.time.LocalDate

/**
 * Dot Plus notification modes
 */
enum class NotificationMode {
    /** Remind daily */
    DAILY,
    /** Remind only on new version */
    ON_NEW_VERSION,
    /** Don't show anymore */
    NEVER
}

/**
 * Stores settings for Dot Plus availability notifications
 */
@Service(Service.Level.APP)
@State(
    name = "DotPlusNotificationSettings",
    storages = [Storage("DotSupportSettings.xml")]
)
class DotPlusNotificationSettings : PersistentStateComponent<DotPlusNotificationSettings.State> {

    data class State(
        var notificationMode: String = NotificationMode.DAILY.name,
        var lastCheckDate: String = "",
        var lastKnownVersion: String = "",
        var dotPlusWasAvailable: Boolean = false
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var notificationMode: NotificationMode
        get() = try {
            NotificationMode.valueOf(myState.notificationMode)
        } catch (e: Exception) {
            NotificationMode.DAILY
        }
        set(value) {
            myState.notificationMode = value.name
        }

    var lastCheckDate: LocalDate?
        get() = myState.lastCheckDate.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
        set(value) {
            myState.lastCheckDate = value?.toString() ?: ""
        }

    var lastKnownVersion: String
        get() = myState.lastKnownVersion
        set(value) {
            myState.lastKnownVersion = value
        }

    var dotPlusWasAvailable: Boolean
        get() = myState.dotPlusWasAvailable
        set(value) {
            myState.dotPlusWasAvailable = value
        }

    /**
     * Should we check Dot Plus availability today?
     */
    fun shouldCheckToday(): Boolean {
        return when (notificationMode) {
            NotificationMode.NEVER -> false
            NotificationMode.DAILY -> lastCheckDate != LocalDate.now()
            NotificationMode.ON_NEW_VERSION -> lastCheckDate != LocalDate.now()
        }
    }

    /**
     * Should we show a notification?
     */
    fun shouldShowNotification(currentVersion: String?, isAvailable: Boolean): Boolean {
        if (!isAvailable) return false

        return when (notificationMode) {
            NotificationMode.NEVER -> false
            NotificationMode.DAILY -> true
            NotificationMode.ON_NEW_VERSION -> {
                // Show only if new version or first detection
                currentVersion != null && (
                    !dotPlusWasAvailable || 
                    (lastKnownVersion.isNotEmpty() && currentVersion != lastKnownVersion)
                )
            }
        }
    }

    /**
     * Save check result
     */
    fun recordCheck(version: String?, isAvailable: Boolean) {
        lastCheckDate = LocalDate.now()
        if (isAvailable && version != null) {
            lastKnownVersion = version
            dotPlusWasAvailable = true
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(): DotPlusNotificationSettings {
            return ApplicationManager.getApplication().getService(DotPlusNotificationSettings::class.java)
        }
    }
}
