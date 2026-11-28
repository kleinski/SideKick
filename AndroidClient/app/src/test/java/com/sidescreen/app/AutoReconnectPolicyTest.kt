package com.sidescreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReconnectPolicyTest {
    @Test
    fun coldStartWithSingleOnlineMacConnects() {
        val target =
            AutoReconnectPolicy.chooseTargetHostId(
                autoConnectEnabled = true,
                suppressed = false,
                onlineHostIds = setOf("mac-a"),
                preferredReconnectHostId = null,
            )
        assertEquals("mac-a", target)
    }

    @Test
    fun coldStartWithMultipleOnlineMacsDoesNotGuess() {
        val target =
            AutoReconnectPolicy.chooseTargetHostId(
                autoConnectEnabled = true,
                suppressed = false,
                onlineHostIds = setOf("mac-a", "mac-b"),
                preferredReconnectHostId = null,
            )
        assertNull(target)
    }

    @Test
    fun unplannedDropReconnectsPreferredMacEvenWhenSecondMacIsOnline() {
        val target =
            AutoReconnectPolicy.chooseTargetHostId(
                autoConnectEnabled = true,
                suppressed = false,
                onlineHostIds = setOf("mac-a", "mac-b"),
                preferredReconnectHostId = "mac-b",
            )
        assertEquals("mac-b", target)
    }

    @Test
    fun suppressedBlocksAutoConnect() {
        val target =
            AutoReconnectPolicy.chooseTargetHostId(
                autoConnectEnabled = true,
                suppressed = true,
                onlineHostIds = setOf("mac-a"),
                preferredReconnectHostId = "mac-a",
            )
        assertNull(target)
    }

    @Test
    fun disabledBlocksAutoConnect() {
        val target =
            AutoReconnectPolicy.chooseTargetHostId(
                autoConnectEnabled = false,
                suppressed = false,
                onlineHostIds = setOf("mac-a"),
                preferredReconnectHostId = "mac-a",
            )
        assertNull(target)
    }

    @Test
    fun suppressionLiftsOnceDisconnectedMacGoesOffline() {
        assertFalse(
            AutoReconnectPolicy.shouldLiftSuppression(
                suppressed = true,
                suppressedHostId = "mac-a",
                onlineHostIds = setOf("mac-a"),
            ),
        )
        assertTrue(
            AutoReconnectPolicy.shouldLiftSuppression(
                suppressed = true,
                suppressedHostId = "mac-a",
                onlineHostIds = setOf("mac-b"),
            ),
        )
        assertTrue(
            AutoReconnectPolicy.shouldLiftSuppression(
                suppressed = true,
                suppressedHostId = "mac-a",
                onlineHostIds = emptySet(),
            ),
        )
    }
}
