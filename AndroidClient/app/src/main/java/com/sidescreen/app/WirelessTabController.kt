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
    )

    enum class State { FIRST_TIME, CONNECTING, CONNECTED, PAIRED_IDLE, REPAIR_NEEDED, PERM_DENIED }

    private var state: State = State.FIRST_TIME
    private var onlineHostIds: Set<String> = emptySet()
    private var activeEntry: PairedHostStorage.Entry? = null
    private var scanJob: Job? = null

    /**
     * Set to true after an explicit Disconnect or error so we stay on the Mac selection list
     * instead of immediately auto-reconnecting in a loop.
     */
    private var suppressAutoConnect = false

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
                        renderPairedMacsList(all)
                    }
                }
            }
        }
    }

    /**
     * Called when the TCP stream goes down (user tapped Disconnect, network drop, etc).
     * Move the UI to the multi-Mac list without auto-connecting immediately.
     */
    fun onStreamDisconnected() {
        suppressAutoConnect = true
        val all = storage.loadAll()
        if (all.isEmpty()) {
            transition(State.FIRST_TIME)
            return
        }
        val primary = all.first()
        views.idleMacName.text = primary.macName
        views.idleMacIp.text = "${primary.host}:${primary.port}"
        transition(State.PAIRED_IDLE)
        renderPairedMacsList(all)
        scanAndRefreshMacList(allowAutoConnect = false)
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
                scanAndRefreshMacList(allowAutoConnect = !suppressAutoConnect)
            }
        }
    }

    fun scanAndRefreshMacList(allowAutoConnect: Boolean) {
        scanJob?.cancel()
        val entries = storage.loadAll()
        if (entries.isEmpty()) {
            transition(State.FIRST_TIME)
            return
        }
        views.scanStatusText?.text = "Scanning Wi‑Fi for paired MacBooks…"
        scanJob =
            scope.launch {
                val reachableIds = discovery.probeReachablePairedMacs(entries, storage)
                withContext(Dispatchers.Main) {
                    if (state != State.PAIRED_IDLE) return@withContext
                    onlineHostIds = reachableIds
                    val refreshedEntries = storage.loadAll()
                    val onlineMacs = refreshedEntries.filter { it.hostId in reachableIds }

                    when {
                        onlineMacs.size == 1 && allowAutoConnect -> {
                            val soleMac = onlineMacs.first()
                            views.scanStatusText?.text = "${soleMac.macName} is online — connecting automatically…"
                            connectToEntry(soleMac)
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
                            views.scanStatusText?.text =
                                "No paired MacBook reachable yet. Make sure SideKick is running on your Mac."
                            renderPairedMacsList(refreshedEntries)
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
                orientation = LinearLayout.VERTICAL
                val pad = dp(14)
                setPadding(pad, pad, pad, pad)
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(if (isPreselected) Color.parseColor("#224CAF50") else Color.parseColor("#18FFFFFF"))
                        setStroke(
                            dp(if (isPreselected) 2 else 1),
                            if (isPreselected) Color.parseColor("#4CAF50") else Color.parseColor("#33FFFFFF"),
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

        // Top row: status dot + Mac name + badge + forget button
        val topRow =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        val dot =
            TextView(ctx).apply {
                text = if (isOnline) "●" else "○"
                setTextColor(if (isOnline) Color.parseColor("#4ade80") else Color.parseColor("#777777"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, 0, dp(8), 0)
            }
        topRow.addView(dot)

        val titleCol =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

        val nameText =
            TextView(ctx).apply {
                text = entry.macName
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
            }
        titleCol.addView(nameText)

        val statusLabel = if (isOnline) "Online" else "Offline"
        val preselectLabel = if (isPreselected) " • Last connected (Selected)" else ""
        val subText =
            TextView(ctx).apply {
                text = "${entry.host}:${entry.port} • $statusLabel$preselectLabel"
                setTextColor(if (isOnline) Color.parseColor("#A7F3D0") else Color.parseColor("#999999"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
        titleCol.addView(subText)
        topRow.addView(titleCol)

        val forgetBtn =
            TextView(ctx).apply {
                text = "Forget"
                setTextColor(Color.parseColor("#fca5a5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10), dp(6), dp(6), dp(6))
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
        topRow.addView(forgetBtn)
        card.addView(topRow)

        val connectBtn =
            MaterialButton(ctx).apply {
                text = if (isPreselected) "Connect to ${entry.macName}" else "Switch to ${entry.macName}"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                cornerRadius = dp(12)
                backgroundTintList =
                    ColorStateList.valueOf(
                        if (isPreselected) Color.parseColor("#4CAF50") else Color.parseColor("#2E7D32"),
                    )
                setTextColor(Color.WHITE)
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(46),
                    ).apply {
                        topMargin = dp(10)
                    }
                setOnClickListener {
                    connectToEntry(entry)
                }
            }
        card.addView(connectBtn)

        return card
    }

    fun connectToEntry(entry: PairedHostStorage.Entry) {
        suppressAutoConnect = false
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
        suppressAutoConnect = true
        val cached = activeEntry ?: storage.load()
        when (error) {
            is StreamClient.WirelessConnectError.NetworkUnreachable -> {
                val all = storage.loadAll()
                if (all.size > 1) {
                    // When multiple Macs are paired, return directly to the multi-Mac list
                    // so the user can pick the other Mac with 1 tap instead of a dead-end error screen.
                    transition(State.PAIRED_IDLE)
                    views.scanStatusText?.text =
                        "⚠ Could not reach ${cached?.macName ?: "Mac"} (${cached?.host}:${cached?.port}). Pick another Mac or scan QR:"
                    renderPairedMacsList(all)
                    return
                }
                views.repairTitle.text = "⚠ Couldn't reach Mac"
                views.repairMessage.text =
                    if (cached != null) {
                        "No response from ${cached.macName} at ${cached.host}:${cached.port}.\n\n" +
                            "The Mac may have switched WiFi networks, changed its port, or is not " +
                            "running. Open SideKick on the Mac and scan the new QR to re-pair."
                    } else {
                        "No response from your Mac. Make sure both devices are on the same WiFi " +
                            "and the Mac app is running, then scan the QR again."
                    }
                transition(State.REPAIR_NEEDED)
            }
            is StreamClient.WirelessConnectError.TokenRejected -> {
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
    ) {
        views.connectedMacName.text = macName
        views.connectedMacIp.text = ip
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
