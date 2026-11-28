package com.sidescreen.app

/**
 * Pure decision logic for wireless Auto-Connect / Auto-Reconnect.
 */
object AutoReconnectPolicy {
    /**
     * Decide which paired Mac (if any) should be connected automatically.
     *
     * Rules:
     * 1. Auto-connect must be enabled and not currently suppressed.
     * 2. If [preferredReconnectHostId] is set (after an unplanned stream drop) and is
     *    currently online, reconnect directly to that Mac — even when other Macs are online.
     * 3. Otherwise, auto-connect only when exactly 1 paired Mac is online (never guess
     *    between multiple online Macs on cold start or resume).
     */
    fun chooseTargetHostId(
        autoConnectEnabled: Boolean,
        suppressed: Boolean,
        onlineHostIds: Set<String>,
        preferredReconnectHostId: String?,
    ): String? {
        if (!autoConnectEnabled || suppressed) return null
        if (preferredReconnectHostId != null && preferredReconnectHostId in onlineHostIds) {
            return preferredReconnectHostId
        }
        return if (onlineHostIds.size == 1) onlineHostIds.first() else null
    }

    /**
     * When auto-connect was suppressed after an explicit disconnect (tablet Disconnect button
     * or Mac-side ⌘⇧D), lift the suppression once the disconnected Mac is observed offline —
     * so when it later wakes up or restarts, auto-connect triggers naturally.
     */
    fun shouldLiftSuppression(
        suppressed: Boolean,
        suppressedHostId: String?,
        onlineHostIds: Set<String>,
    ): Boolean {
        if (!suppressed) return false
        return if (suppressedHostId != null) {
            suppressedHostId !in onlineHostIds
        } else {
            onlineHostIds.isEmpty()
        }
    }
}
