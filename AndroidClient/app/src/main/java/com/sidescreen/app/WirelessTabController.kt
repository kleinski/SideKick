package com.sidescreen.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-Mac UI controller for the Wireless tab on Android.
 *
 * Supports:
 * - Storing & displaying multiple paired Macs (most recently connected pre-selected at the top)
 * - mDNS (`_sidescreen._tcp`) + TCP reachability scanning
 * - Smart auto-connect on initial launch:
 *   - If exactly 1 paired Mac is online on Wi-Fi → connects automatically
 *   - If 2+ paired Macs are online simultaneously → shows the selection list with the last-used Mac pre-selected
 */
class WirelessTabController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val views: Views,
    private val storage: PairedHostStorage,
    private val discovery: LanHostDiscovery,
    private val cameraPerm: CameraPermissionManager,
    private val onConnectRequested: (
        host: String,
        port: Int,
        token: ByteArray,
        deviceName: String,
        macName: String,
    ) -> Unit,
    /** Code pairing (issue #35): open the "enter code" dialog. */
    private val onCodePairRequested: () -> Unit,
) {
    data class Views(
        val connecting: View,
        val firstTime: View,
        val connected: View,
        val pairedIdle: View,
        val repair: View,
        val permDenied: View,
        val scanButton: Button,
        val rescanButton: Button,
        val enterCodeButton: Button,
        val repairEnterCodeButton: Button,
        val permDeniedEnterCodeButton: Button,
        val disconnectButton: Button,
        val forgetButton: Button,
        val reconnectButton: Button,
        val idleForgetButton: Button,
        val openSettingsButton: Button,
        val connectedMacName: TextView,
        val connectedMacIp: TextView,
        val connectingLabel: TextView,
        val connectingSubtitle: TextView,
        val idleMacName: TextView,
        val idleMacIp: TextView,
        val repairTitle: TextView,
        val repairMessage: TextView,
        val pairedMacsHeader: TextView? = null,
        val refreshScanButton: TextView? = null,
        val scanStatusText: TextView? = null,
        val pairedMacsListContainer: LinearLayout? = null,
        val addMacScanButton: Button? = null,
        val addMacCodeButton: Button? = null,
        val connectedEncryptionStatus: TextView? = null,
    )

    enum class State { FIRST_TIME, CONNECTING, CONNECTED, PAIRED_IDLE, REPAIR_NEEDED, PERM_DENIED }

    private var state: State = State.FIRST_TIME
    private val prefs = PreferencesManager(activity)
    private var onlineHostIds: Set<String> = emptySet()
    private var activeEntry: PairedHostStorage.Entry? = null
    private var scanJob: Job? = null
    private var periodicScanJob: Job? = null
    private var isActivityResumed = true
    private var wasPaused = false

    /**
     * Set to true after an explicit Disconnect (tablet button or Mac-side ⌘⇧D) or auth error
     * so we stay on the Mac selection list instead of immediately auto-reconnecting in a loop.
     * Lifted automatically when the suppressed Mac goes offline→online or on app resume.
     */
    private var suppressAutoConnect = false
    private var suppressedHostId: String? = null
    private var preferredReconnectHostId: String? = null
    private var autoConnectRetryPending = false

    fun bind() {
        views.scanButton.setOnClickListener { triggerScan() }
        views.rescanButton.setOnClickListener { triggerScan() }
        views.addMacScanButton?.setOnClickListener { triggerScan() }
        views.enterCodeButton.setOnClickListener { onCodePairRequested() }
        views.repairEnterCodeButton.setOnClickListener { onCodePairRequested() }
        views.permDeniedEnterCodeButton.setOnClickListener { onCodePairRequested() }
        views.addMacCodeButton?.setOnClickListener { onCodePairRequested() }
        views.openSettingsButton.setOnClickListener { cameraPerm.openAppSettings() }

        views.refreshScanButton?.setOnClickListener {
            scanAndRefreshMacList(allowAutoConnect = false)
        }

        views.forgetButton.setOnClickListener {
            val current = activeEntry ?: storage.load()
            if (current != null) {
                storage.remove(current.hostId)
            } else {
                storage.clear()
            }
            if (storage.loadAll().isEmpty()) {
                transition(State.FIRST_TIME)
            } else {
                suppressAutoConnect = true
                show()
            }
        }
        views.idleForgetButton.setOnClickListener {
            storage.clear()
            transition(State.FIRST_TIME)
        }
        views.reconnectButton.setOnClickListener {
            val entry =
                storage.load() ?: run {
                    transition(State.FIRST_TIME)
                    return@setOnClickListener
                }
            connectToEntry(entry)
        }

        discovery.onHostsUpdated = {
            activity.runOnUiThread {
                if (state == State.PAIRED_IDLE) {
                    val mdns = discovery.currentDiscovered()
                    var changed = false
                    for (d in mdns) {
                        val key = d.hostId ?: d.macName
                        if (storage.updateEndpoint(key, d.host, d.port) != null) {
                            changed = true
                        }
                    }
                    val all = storage.loadAll()
                    val mdnsOnline =
                        all
                            .filter { e ->
                                mdns.any {
                                    (it.hostId != null && it.hostId == e.hostId) ||
                                        it.macName.equals(e.macName, ignoreCase = true)
                                }
                            }.map { it.hostId }
                            .toSet()
                    if (changed || !mdnsOnline.all { it in onlineHostIds }) {
                        onlineHostIds = onlineHostIds + mdnsOnline
                        val targetId =
                            AutoReconnectPolicy.chooseTargetHostId(
                                autoConnectEnabled = prefs.autoConnectEnabled,
                                suppressed = suppressAutoConnect,
                                onlineHostIds = onlineHostIds,
                                preferredReconnectHostId = preferredReconnectHostId,
                            )
                        val targetMac = targetId?.let { id -> all.firstOrNull { it.hostId == id } }
                        if (targetMac != null) {
                            views.scanStatusText?.text = "${targetMac.macName} is online — connecting automatically…"
                            connectToEntry(targetMac)
                        } else {
                            renderPairedMacsList(all)
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the TCP stream goes down.
     * - [userInitiated] == true (tablet Disconnect button): suppress auto-connect until the Mac
     *   goes offline→online or the app returns to foreground (onResume).
     * - [userInitiated] == false (network drop, Mac sleep, heartbeat timeout, or Mac ⌘⇧D):
     *   probe immediately. If the same Mac is still immediately reachable, treat it as a
     *   Mac-initiated Disconnect (⌘⇧D) and stay idle. Otherwise arm auto-reconnect for that Mac.
     */
    fun onStreamDisconnected(userInitiated: Boolean = false) {
        val lastHostId = activeEntry?.hostId ?: storage.load()?.hostId
        autoConnectRetryPending = false
        val all = storage.loadAll()
        if (all.isEmpty()) {
            suppressAutoConnect = false
            suppressedHostId = null
            preferredReconnectHostId = null
            transition(State.FIRST_TIME)
            return
        }
        val primary = all.first()
        views.idleMacName.text = primary.macName
        views.idleMacIp.text = "${primary.host}:${primary.port}"
        transition(State.PAIRED_IDLE)
        renderPairedMacsList(all)

        if (userInitiated) {
            suppressAutoConnect = true
            suppressedHostId = lastHostId
            preferredReconnectHostId = null
            scanAndRefreshMacList(allowAutoConnect = false)
        } else {
            // Probe immediately to distinguish Mac-side Disconnect (Mac still listening)
            // from an unplanned drop (Mac asleep / WiFi drop / Mac restarting).
            scanAndRefreshMacList(
                allowAutoConnect = false,
                postDropHostId = lastHostId,
            )
        }
    }

    private fun transition(next: State) {
        android.util.Log.i("WirelessTabController", "transition $state → $next")
        state = next
        views.connecting.visibility = if (next == State.CONNECTING) View.VISIBLE else View.GONE
        views.firstTime.visibility = if (next == State.FIRST_TIME) View.VISIBLE else View.GONE
        views.connected.visibility = if (next == State.CONNECTED) View.VISIBLE else View.GONE
        views.pairedIdle.visibility = if (next == State.PAIRED_IDLE) View.VISIBLE else View.GONE
        views.repair.visibility = if (next == State.REPAIR_NEEDED) View.VISIBLE else View.GONE
        views.permDenied.visibility = if (next == State.PERM_DENIED) View.VISIBLE else View.GONE
        updatePeriodicScanLoop()
    }

    fun onResume() {
        isActivityResumed = true
        if (wasPaused) {
            wasPaused = false
            if (state == State.PAIRED_IDLE) {
                suppressAutoConnect = false
                suppressedHostId = null
                autoConnectRetryPending = false
                scanAndRefreshMacList(allowAutoConnect = prefs.autoConnectEnabled, silent = false)
            }
        }
        updatePeriodicScanLoop()
    }

    fun onPause() {
        isActivityResumed = false
        wasPaused = true
        periodicScanJob?.cancel()
        periodicScanJob = null
    }

    fun onScanIntervalChanged() {
        updatePeriodicScanLoop()
    }

    fun clearSuppressionAndScan() {
        suppressAutoConnect = false
        suppressedHostId = null
        autoConnectRetryPending = false
        if (state == State.PAIRED_IDLE) {
            scanAndRefreshMacList(allowAutoConnect = prefs.autoConnectEnabled)
        }
    }

    private fun updatePeriodicScanLoop() {
        periodicScanJob?.cancel()
        periodicScanJob = null
        val intervalSec = prefs.scanIntervalSeconds
        if (!isActivityResumed || state != State.PAIRED_IDLE || intervalSec <= 0 || storage.loadAll().isEmpty()) {
            return
        }
        periodicScanJob =
            scope.launch {
                while (isActivityResumed && state == State.PAIRED_IDLE && prefs.scanIntervalSeconds > 0) {
                    delay(prefs.scanIntervalSeconds * 1_000L)
                    if (!isActivityResumed || state != State.PAIRED_IDLE) break
                    scanAndRefreshMacList(
                        allowAutoConnect = prefs.autoConnectEnabled,
                        silent = true,
                    )
                }
            }
    }

    /**
     * Called when the Wireless tab becomes visible.
     * Performs a quick mDNS + TCP reachability scan across all paired Macs:
     * - If 1 paired Mac is online (and not right after manual disconnect): auto-connects!
     * - If 2+ paired Macs are online (or 0 online): shows the list with the last-connected Mac pre-selected.
     */
    fun show() {
        val all = storage.loadAll()
        when {
            cameraPerm.isPermanentlyDenied() && all.isEmpty() -> transition(State.PERM_DENIED)
            all.isEmpty() -> transition(State.FIRST_TIME)
            else -> {
                val primary = all.first()
                views.idleMacName.text = primary.macName
                views.idleMacIp.text = "${primary.host}:${primary.port}"
                transition(State.PAIRED_IDLE)
                renderPairedMacsList(all)
                scanAndRefreshMacList(allowAutoConnect = !suppressAutoConnect && prefs.autoConnectEnabled)
            }
        }
    }

    fun scanAndRefreshMacList(
        allowAutoConnect: Boolean,
        silent: Boolean = false,
        postDropHostId: String? = null,
    ) {
        scanJob?.cancel()
        val entries = storage.loadAll()
        if (entries.isEmpty()) {
            transition(State.FIRST_TIME)
            return
        }
        if (!silent) {
            views.scanStatusText?.text = "Scanning Wi‑Fi for paired MacBooks…"
        }
        scanJob =
            scope.launch {
                val reachableIds = discovery.probeReachablePairedMacs(entries, storage)
                withContext(Dispatchers.Main) {
                    if (state != State.PAIRED_IDLE) return@withContext
                    onlineHostIds = reachableIds
                    val refreshedEntries = storage.loadAll()
                    val onlineMacs = refreshedEntries.filter { it.hostId in reachableIds }

                    // Handle immediate post-drop classification (unplanned drop vs Mac-side ⌘⇧D)
                    if (postDropHostId != null) {
                        if (postDropHostId in reachableIds) {
                            // Mac is still immediately listening -> Mac-initiated Disconnect (⌘⇧D).
                            // Stay on the Mac list instead of reconnecting in a tight loop.
                            suppressAutoConnect = true
                            suppressedHostId = postDropHostId
                            preferredReconnectHostId = null
                        } else {
                            // Mac went unreachable -> unplanned drop (sleep / WiFi / restart).
                            // Arm auto-reconnect for this Mac as soon as it returns.
                            suppressAutoConnect = false
                            suppressedHostId = null
                            preferredReconnectHostId = postDropHostId
                        }
                    } else if (AutoReconnectPolicy.shouldLiftSuppression(suppressAutoConnect, suppressedHostId, reachableIds)) {
                        suppressAutoConnect = false
                        suppressedHostId = null
                    }

                    val targetId =
                        AutoReconnectPolicy.chooseTargetHostId(
                            autoConnectEnabled = prefs.autoConnectEnabled,
                            suppressed = suppressAutoConnect || !allowAutoConnect,
                            onlineHostIds = reachableIds,
                            preferredReconnectHostId = preferredReconnectHostId,
                        )
                    val targetMac = targetId?.let { id -> refreshedEntries.firstOrNull { it.hostId == id } }

                    when {
                        targetMac != null -> {
                            views.scanStatusText?.text = "${targetMac.macName} is online — connecting automatically…"
                            connectToEntry(targetMac)
                        }
                        onlineMacs.size > 1 -> {
                            views.scanStatusText?.text =
                                "${onlineMacs.size} MacBooks online — tap to connect (last used is pre-selected):"
                            renderPairedMacsList(refreshedEntries)
                        }
                        onlineMacs.size == 1 -> {
                            views.scanStatusText?.text =
                                "1 MacBook online — tap Connect to start streaming:"
                            renderPairedMacsList(refreshedEntries)
                        }
                        else -> {
                            val waitingName =
                                preferredReconnectHostId?.let { id ->
                                    refreshedEntries.firstOrNull { it.hostId == id }?.macName
                                }
                            views.scanStatusText?.text =
                                if (waitingName != null && prefs.autoConnectEnabled) {
                                    "Waiting for $waitingName to come back online…"
                                } else {
                                    "No paired MacBook reachable yet. Make sure SideKick is running on your Mac."
                                }
                            renderPairedMacsList(refreshedEntries)
                            if (allowAutoConnect && prefs.autoConnectEnabled && !autoConnectRetryPending) {
                                autoConnectRetryPending = true
                                scope.launch {
                                    delay(1_500L)
                                    autoConnectRetryPending = false
                                    if (state == State.PAIRED_IDLE && !suppressAutoConnect) {
                                        scanAndRefreshMacList(allowAutoConnect = true, silent = true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun renderPairedMacsList(entries: List<PairedHostStorage.Entry>) {
        val container = views.pairedMacsListContainer ?: return
        container.removeAllViews()
        views.pairedMacsHeader?.text = "Paired MacBooks (${entries.size})"

        entries.forEachIndexed { index, entry ->
            val isPreselected = index == 0
            val isOnline = entry.hostId in onlineHostIds
            val card = buildMacRowView(entry, isPreselected = isPreselected, isOnline = isOnline)
            container.addView(card)
        }
    }

    private fun buildMacRowView(
        entry: PairedHostStorage.Entry,
        isPreselected: Boolean,
        isOnline: Boolean,
    ): View {
        val ctx = activity
        val card =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(if (isPreselected) Color.parseColor("#1E293B") else Color.parseColor("#14FFFFFF"))
                        setStroke(
                            dp(1),
                            if (isPreselected) Color.parseColor("#60A5FA") else Color.parseColor("#24FFFFFF"),
                        )
                    }
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = dp(10)
                    }
            }

        val dot =
            TextView(ctx).apply {
                text = "●"
                setTextColor(if (isOnline) Color.parseColor("#4ADE80") else Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, dp(10), 0)
            }
        card.addView(dot)

        val titleCol =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

        val nameText =
            TextView(ctx).apply {
                text = entry.macName
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
            }
        titleCol.addView(nameText)

        val statusLabel = if (isOnline) "Online" else "Offline"
        val preselectLabel = if (isPreselected) " • Last connected" else ""
        val subText =
            TextView(ctx).apply {
                text = "${entry.host} • $statusLabel$preselectLabel"
                setTextColor(if (isOnline) Color.parseColor("#4ADE80") else Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
        titleCol.addView(subText)
        card.addView(titleCol)

        val connectBtn =
            MaterialButton(ctx).apply {
                text = "Connect"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                cornerRadius = dp(20)
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(16), 0, dp(16), 0)
                if (isPreselected) {
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                    setTextColor(Color.WHITE)
                } else {
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1FFFFFFF"))
                    strokeColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
                    strokeWidth = dp(1)
                    setTextColor(Color.parseColor("#E2E8F0"))
                }
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(38),
                    ).apply {
                        marginStart = dp(8)
                    }
                setOnClickListener {
                    connectToEntry(entry)
                }
            }
        card.addView(connectBtn)

        val forgetBtn =
            TextView(ctx).apply {
                text = "✕"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(12), dp(8), dp(6), dp(8))
                setOnClickListener {
                    storage.remove(entry.hostId)
                    val remaining = storage.loadAll()
                    if (remaining.isEmpty()) {
                        transition(State.FIRST_TIME)
                    } else {
                        renderPairedMacsList(remaining)
                    }
                }
            }
        card.addView(forgetBtn)

        return card
    }

    fun connectToEntry(entry: PairedHostStorage.Entry) {
        suppressAutoConnect = false
        suppressedHostId = null
        activeEntry = entry
        storage.markConnected(entry)
        val deviceName = defaultDeviceName(activity)
        showConnecting("Connecting to ${entry.macName}", "${entry.host}:${entry.port}")
        onConnectRequested(entry.host, entry.port, entry.token, deviceName, entry.macName)
    }

    fun onScanResult(url: String) {
        val parsed = PairingURL.parse(url) ?: return
        val deviceName = defaultDeviceName(activity)
        val entry =
            PairedHostStorage.Entry(
                host = parsed.host,
                port = parsed.port,
                token = parsed.token,
                macName = parsed.macName,
                hostId = parsed.hostId ?: PairedHostStorage.deriveId(parsed.macName, parsed.token),
            )
        activeEntry = entry
        storage.save(entry)
        showConnecting("Connecting to ${parsed.macName}", "${parsed.host}:${parsed.port}")
        onConnectRequested(parsed.host, parsed.port, parsed.token, deviceName, parsed.macName)
    }

    /**
     * Code pairing succeeded (issue #35): the Mac issued the real token. Save it
     * into the multi-host list and connect through the normal wireless handshake.
     */
    fun onCodePairComplete(
        host: String,
        port: Int,
        token: ByteArray,
        macName: String,
    ) {
        val deviceName = defaultDeviceName(activity)
        val entry = PairedHostStorage.Entry(host, port, token, macName)
        activeEntry = entry
        storage.save(entry)
        showConnecting("Connecting to $macName", "$host:$port")
        onConnectRequested(host, port, token, deviceName, macName)
    }

    fun onConnectError(error: StreamClient.WirelessConnectError) {
        val cached = activeEntry ?: storage.load()
        when (error) {
            is StreamClient.WirelessConnectError.NetworkUnreachable -> {
                val all = storage.loadAll()
                if (all.isNotEmpty()) {
                    // Stay in PAIRED_IDLE instead of dead-ending in REPAIR_NEEDED so the
                    // periodic auto-scan & Bonjour listener can reconnect when the Mac wakes up.
                    suppressAutoConnect = false
                    suppressedHostId = null
                    preferredReconnectHostId = cached?.hostId
                    transition(State.PAIRED_IDLE)
                    views.scanStatusText?.text =
                        if (prefs.autoConnectEnabled) {
                            "⚠ Could not reach ${cached?.macName ?: "Mac"} — waiting to reconnect…"
                        } else {
                            "⚠ Could not reach ${cached?.macName ?: "Mac"} (${cached?.host}:${cached?.port}). Tap to retry:"
                        }
                    renderPairedMacsList(all)
                    return
                }
                suppressAutoConnect = true
                views.repairTitle.text = "⚠ Couldn't reach Mac"
                views.repairMessage.text =
                    "No response from your Mac. Make sure both devices are on the same WiFi " +
                        "and the Mac app is running, then scan the QR again."
                transition(State.REPAIR_NEEDED)
            }
            is StreamClient.WirelessConnectError.TokenRejected -> {
                suppressAutoConnect = true
                preferredReconnectHostId = null
                views.repairTitle.text = "⚠ Re-pair required"
                views.repairMessage.text =
                    if (cached != null) {
                        "${cached.macName} reset its pairing token (e.g. Reset Token clicked, or " +
                            "reinstalled). Scan the new QR to pair again."
                    } else {
                        "The Mac reset its pairing token. Scan the new QR to pair again."
                    }
                transition(State.REPAIR_NEEDED)
            }
            is StreamClient.WirelessConnectError.ProtocolError -> {
                suppressAutoConnect = true
                preferredReconnectHostId = null
                views.repairTitle.text = "⚠ Connection error"
                views.repairMessage.text = "Couldn't complete the secure handshake with the Mac. Scan the QR again."
                transition(State.REPAIR_NEEDED)
            }
        }
    }

    private fun showConnecting(
        title: String,
        subtitle: String,
    ) {
        views.connectingLabel.text = title
        views.connectingSubtitle.text = subtitle
        transition(State.CONNECTING)
    }

    fun onConnectSuccess(
        macName: String,
        ip: String,
        encrypted: Boolean = true,
    ) {
        suppressAutoConnect = false
        suppressedHostId = null
        preferredReconnectHostId = null
        autoConnectRetryPending = false
        views.connectedMacName.text = macName
        views.connectedMacIp.text = ip
        views.connectedEncryptionStatus?.apply {
            if (encrypted) {
                text = "🔒 End-to-End Encrypted (AES-256-GCM)"
                setTextColor(Color.parseColor("#4ade80"))
            } else {
                text = "🔓 Unencrypted (Encryption off on Mac)"
                setTextColor(Color.parseColor("#FFB74D"))
            }
        }
        transition(State.CONNECTED)
    }

    fun onCameraPermissionResult(granted: Boolean) {
        if (granted) {
            launchScanner()
        } else if (cameraPerm.isPermanentlyDenied()) {
            transition(State.PERM_DENIED)
        }
    }

    private fun triggerScan() {
        if (cameraPerm.isPermanentlyDenied()) {
            transition(State.PERM_DENIED)
            return
        }
        if (!cameraPerm.isGranted()) {
            cameraPerm.request(REQ_CAMERA)
            return
        }
        launchScanner()
    }

    private fun launchScanner() {
        val intent = Intent(activity, QRScannerActivity::class.java)
        activity.startActivityForResult(intent, REQ_SCAN)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            activity.resources.displayMetrics,
        ).toInt()

    companion object {
        const val REQ_SCAN = 1001
        const val REQ_CAMERA = 1002

        fun defaultDeviceName(context: Context): String {
            val resolver = context.contentResolver
            val candidates =
                sequence {
                    yield(runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }.getOrNull())
                    yield(runCatching { Settings.Secure.getString(resolver, "bluetooth_name") }.getOrNull())
                    yield(android.os.Build.MODEL)
                }
            var name = candidates.firstOrNull { !it.isNullOrBlank() }?.trim() ?: "Android"
            while (name.toByteArray(Charsets.UTF_8).size > 64) {
                name = name.dropLast(1)
            }
            return name
        }
    }
}
