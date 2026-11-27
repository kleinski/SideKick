package com.sidescreen.app

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var showStatsOverlay: Boolean
        get() = prefs.getBoolean("show_stats", true)
        set(value) = prefs.edit().putBoolean("show_stats", value).apply()

    var overlayOpacity: Float
        get() = prefs.getFloat("overlay_opacity", 0.8f)
        set(value) = prefs.edit().putFloat("overlay_opacity", value).apply()

    var overlayX: Float
        get() = prefs.getFloat("overlay_x", -1f)
        set(value) = prefs.edit().putFloat("overlay_x", value).apply()

    var overlayY: Float
        get() = prefs.getFloat("overlay_y", -1f)
        set(value) = prefs.edit().putFloat("overlay_y", value).apply()

    var settingsButtonX: Float
        get() = prefs.getFloat("settings_x", -1f)
        set(value) = prefs.edit().putFloat("settings_x", value).apply()

    var settingsButtonY: Float
        get() = prefs.getFloat("settings_y", -1f)
        set(value) = prefs.edit().putFloat("settings_y", value).apply()

    // Corner position: 0=bottom-right, 1=bottom-left, 2=top-right, 3=top-left
    var settingsButtonCorner: Int
        get() = prefs.getInt("settings_corner", 0)
        set(value) = prefs.edit().putInt("settings_corner", value).apply()

    var hideSettingsButton: Boolean
        get() = prefs.getBoolean("hide_settings_button", false)
        set(value) = prefs.edit().putBoolean("hide_settings_button", value).apply()

    var connectionMode: ConnectionMode
        get() = ConnectionMode.fromName(prefs.getString("connection_mode", null))
        set(value) = prefs.edit().putString("connection_mode", value.name).apply()

    var autoConnectEnabled: Boolean
        get() = prefs.getBoolean("auto_connect_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_connect_enabled", value).apply()

    // Periodic Home Screen Mac scan interval in seconds (0 = Off, 5, 10, 30, 60)
    var scanIntervalSeconds: Int
        get() = prefs.getInt("scan_interval_seconds", 10)
        set(value) = prefs.edit().putInt("scan_interval_seconds", value).apply()

    // Individual Gesture toggles (macOS Trackpad style)
    var gesturePinchZoomEnabled: Boolean
        get() = prefs.getBoolean("gesture_pinch_zoom", true)
        set(value) = prefs.edit().putBoolean("gesture_pinch_zoom", value).apply()

    var gestureMissionControlEnabled: Boolean
        get() = prefs.getBoolean("gesture_mission_control", true)
        set(value) = prefs.edit().putBoolean("gesture_mission_control", value).apply()

    var gestureAppExposeEnabled: Boolean
        get() = prefs.getBoolean("gesture_app_expose", true)
        set(value) = prefs.edit().putBoolean("gesture_app_expose", value).apply()

    var gestureSwitchSpacesEnabled: Boolean
        get() = prefs.getBoolean("gesture_switch_spaces", true)
        set(value) = prefs.edit().putBoolean("gesture_switch_spaces", value).apply()

    var gestureShowDesktopEnabled: Boolean
        get() = prefs.getBoolean("gesture_show_desktop", true)
        set(value) = prefs.edit().putBoolean("gesture_show_desktop", value).apply()

    var gestureAppsEnabled: Boolean
        get() = prefs.getBoolean("gesture_apps", true)
        set(value) = prefs.edit().putBoolean("gesture_apps", value).apply()

    // Overlay restore trigger mode: "notch" (top-center handle) or "button" (draggable SideKick floating icon)
    var overlayTriggerMode: String
        get() = prefs.getString("overlay_trigger_mode", TRIGGER_NOTCH) ?: TRIGGER_NOTCH
        set(value) = prefs.edit().putString("overlay_trigger_mode", value).apply()

    var overlayPillXFraction: Float
        get() = prefs.getFloat("overlay_pill_x_frac", -1f)
        set(value) = prefs.edit().putFloat("overlay_pill_x_frac", value).apply()

    var overlayPillYFraction: Float
        get() = prefs.getFloat("overlay_pill_y_frac", -1f)
        set(value) = prefs.edit().putFloat("overlay_pill_y_frac", value).apply()

    var floatingTriggerXFraction: Float
        get() = prefs.getFloat("floating_trigger_x_frac", -1f)
        set(value) = prefs.edit().putFloat("floating_trigger_x_frac", value).apply()

    var floatingTriggerYFraction: Float
        get() = prefs.getFloat("floating_trigger_y_frac", -1f)
        set(value) = prefs.edit().putFloat("floating_trigger_y_frac", value).apply()

    fun resetOverlayPositions() {
        prefs.edit()
            .putFloat("overlay_pill_x_frac", -1f)
            .putFloat("overlay_pill_y_frac", -1f)
            .putFloat("floating_trigger_x_frac", -1f)
            .putFloat("floating_trigger_y_frac", -1f)
            .apply()
    }

    companion object {
        const val TRIGGER_NOTCH = "notch"
        const val TRIGGER_BUTTON = "button"
    }

    /**
     * Stable identity sent to the Mac (wire type 18) so the host can bring back this tablet's
     * settings every time. Random, generated once; survives reconnects and
     * reboots, resets only with a reinstall or clearing app data.
     */
    val deviceId: String
        get() {
            prefs.getString("device_id", null)?.let { return it }
            val fresh = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", fresh).apply()
            return fresh
        }
}
