package com.sidescreen.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.usb.UsbManager
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sidescreen.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

private fun mainDiag(msg: String) = DiagLog.log("MA", msg)

private const val MAC_APP_URL = "https://github.com/kleinski/SideKick/releases"
private const val REPORT_PROBLEM_URL = "https://github.com/kleinski/SideKick/issues"

class MainActivity : AppCompatActivity() {
    private lateinit var wirelessController: WirelessTabController
    private lateinit var appUpdater: AppUpdater
    private lateinit var reviewPrompter: ReviewPrompter
    private val pairedHostStorage by lazy { PairedHostStorage(this) }
    private val lanHostDiscovery by lazy { LanHostDiscovery(this) }
    private val cameraPerm by lazy { CameraPermissionManager(this) }
    private var handoverListener: HandoverListener? = null
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private var videoDecoder: VideoDecoder? = null
    private var streamClient: StreamClient? = null

    /** In-flight code-pairing attempt (issue #35); cancelled when its dialog closes. */
    private var pairingJob: Job? = null
    private var currentSurfaceHolder: SurfaceHolder? = null
    private var currentTextureSurface: Surface? = null
    private var decoderUsingTextureView = false

    // Logical desktop behind the stream, 0 when the Mac predates desktopGeometry.
    // Shown next to the stream size; never used to size the decoder.
    private var desktopWidth = 0
    private var desktopHeight = 0

    private var displayWidth = 0 // 0 = no config received yet
    private var displayHeight = 0 // 0 = no config received yet
    private var displayRotation = 0 // 0, 90, 180, 270 degrees
    private var displayFlipHorizontal = false
    private var displayFlipVertical = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pingJob: kotlinx.coroutines.Job? = null

    // For dragging stats overlay
    private var isDraggingOverlay = false
    private var overlayDx = 0f
    private var overlayDy = 0f

    // Input prediction for low-latency gaming
    private val inputPredictor = InputPredictor()

    // Checklist status handler
    private val checklistHandler = Handler(Looper.getMainLooper())
    private var checklistRunnable: Runnable? = null
    private var isConnected = false // Track connection state to prevent checklist conflicts

    // Demo mode: plays a bundled clip so the app can be tried without a Mac
    private var demoPlayer: DemoPlayer? = null
    private var demoActive = false
    private var demoTouchHintShown = false

    /** True while video is on screen — a real session or the demo. Drives the streaming chrome. */
    private val isStreamingUi get() = isConnected || demoActive
    private var keyboardAllowedOnMac = true
    private var activeStickyModifiers = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DiagLog.init(applicationContext)
        prefs = PreferencesManager(this)

        // Allow rotation based on device sensor when not connected
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enable edge-to-edge display (draw behind system bars and cutout)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        appUpdater = AppUpdater(this)
        reviewPrompter = ReviewPrompter(this) { !isStreamingUi }

        // Apply fullscreen mode immediately
        enableFullscreenMode()

        // Enable performance mode for gaming (after binding is initialized)
        enablePerformanceMode()

        setupSurface()
        setupUI()
        setupDraggableOverlay()
        setupSettingsButton()
        restoreOverlayPosition()
        restoreSettingsButtonPosition()
        startChecklistUpdates()
        setupModeToggle()
        setupWirelessController()
        setupVersionAndAutoConnect()
    }

    private fun appVersionName(): String =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                ).versionName ?: "1.0"
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
            }
        } catch (_: Exception) {
            "1.0"
        }

    private fun setupVersionAndAutoConnect() {
        DisconnectOnTaskRemovedService.onTaskRemovedCallback = {
            streamClient?.disconnect()
        }
        binding.appVersionText.text = "v${appVersionName()}"
        binding.autoConnectSwitch.isChecked = prefs.autoConnectEnabled
        binding.autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (prefs.autoConnectEnabled != isChecked) {
                prefs.autoConnectEnabled = isChecked
                if (isChecked && prefs.connectionMode == ConnectionMode.WIRELESS && !isStreamingUi) {
                    wirelessController.scanAndRefreshMacList(allowAutoConnect = true)
                }
            }
        }
    }

    private fun setupModeToggle() {
        // Restore previous mode and reflect in toggle.
        val saved = prefs.connectionMode
        binding.modeToggleGroup.check(if (saved == ConnectionMode.WIRELESS) R.id.modeWireless else R.id.modeUSB)
        applyModeVisibility(saved)

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.modeWireless) ConnectionMode.WIRELESS else ConnectionMode.USB
            prefs.connectionMode = mode
            applyModeVisibility(mode)
            if (mode == ConnectionMode.WIRELESS) {
                wirelessController.show()
            }
        }
    }

    private fun applyModeVisibility(mode: ConnectionMode) {
        binding.usbModeContent.visibility = if (mode == ConnectionMode.USB) View.VISIBLE else View.GONE
        binding.wirelessModeContent.visibility = if (mode == ConnectionMode.WIRELESS) View.VISIBLE else View.GONE
        // USB checklist polls 127.0.0.1:port every 2s via adb-reverse to verify Mac
        // server reachability. While in Wireless mode that probe creates loopback
        // connections that fight the wireless session for the Mac's single client
        // slot — kicking the wireless client off seconds after it auths. Pause
        // checklist updates whenever Wireless is the active tab.
        if (mode == ConnectionMode.WIRELESS) {
            stopChecklistUpdates()
        } else {
            startChecklistUpdates()
        }
    }

    private fun setupWirelessController() {
        lanHostDiscovery.start()
        handoverListener =
            HandoverListener(pairedHostStorage) { targetMac ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    switchWirelessMac(targetMac, fromTakeover = true)
                }
            }.also { it.start() }

        wirelessController =
            WirelessTabController(
                activity = this,
                scope = lifecycleScope,
                views =
                    WirelessTabController.Views(
                        connecting = binding.wirelessConnecting,
                        firstTime = binding.wirelessFirstTime,
                        connected = binding.wirelessConnected,
                        pairedIdle = binding.wirelessPairedIdle,
                        repair = binding.wirelessTokenMismatch,
                        permDenied = binding.wirelessPermDenied,
                        scanButton = binding.wirelessScanButton,
                        rescanButton = binding.wirelessRescanButton,
                        enterCodeButton = binding.wirelessEnterCodeButton,
                        repairEnterCodeButton = binding.wirelessRepairEnterCodeButton,
                        permDeniedEnterCodeButton = binding.wirelessPermDeniedEnterCodeButton,
                        disconnectButton = binding.wirelessDisconnectButton,
                        forgetButton = binding.wirelessForgetButton,
                        reconnectButton = binding.wirelessReconnectButton,
                        idleForgetButton = binding.wirelessIdleForgetButton,
                        openSettingsButton = binding.wirelessOpenSettingsButton,
                        connectedMacName = binding.connectedMacName,
                        connectedMacIp = binding.connectedMacIp,
                        connectingLabel = binding.connectingLabel,
                        connectingSubtitle = binding.connectingSubtitle,
                        idleMacName = binding.idleMacName,
                        idleMacIp = binding.idleMacIp,
                        repairTitle = binding.repairTitle,
                        repairMessage = binding.repairMessage,
                        pairedMacsHeader = binding.pairedMacsHeader,
                        refreshScanButton = binding.wirelessRefreshScanButton,
                        scanStatusText = binding.wirelessScanStatusText,
                        pairedMacsListContainer = binding.pairedMacsListContainer,
                        addMacScanButton = binding.wirelessAddMacScanButton,
                        addMacCodeButton = binding.wirelessAddMacCodeButton,
                        connectedEncryptionStatus = binding.connectedEncryptionStatus,
                    ),
                storage = pairedHostStorage,
                discovery = lanHostDiscovery,
                cameraPerm = cameraPerm,
                onConnectRequested = { host, port, token, deviceName, macName ->
                    connectWireless(host, port, token, deviceName, macName)
                },
                onCodePairRequested = { showCodePairingDialog() },
            )
        wirelessController.bind()
        binding.wirelessDisconnectButton.setOnClickListener { disconnect() }
        if (prefs.connectionMode == ConnectionMode.WIRELESS) {
            wirelessController.show()
        }
    }

    /**
     * Switches from the currently active stream (or idle screen) to [targetMac],
     * triggered either from the tablet's in-stream Settings dialog or by a Mac takeover signal.
     */
    private fun switchWirelessMac(
        targetMac: PairedHostStorage.Entry,
        fromTakeover: Boolean = false,
    ) {
        mainDiag("Switching wireless Mac to ${targetMac.macName} (${targetMac.host}:${targetMac.port}, takeover=$fromTakeover)")
        if (fromTakeover) {
            android.widget.Toast
                .makeText(
                    this,
                    "Switching to ${targetMac.macName}…",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
        }
        prefs.connectionMode = ConnectionMode.WIRELESS
        binding.modeToggleGroup.check(R.id.modeWireless)
        applyModeVisibility(ConnectionMode.WIRELESS)
        if (isConnected) {
            disconnect()
        }
        wirelessController.connectToEntry(targetMac)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WirelessTabController.REQ_SCAN && resultCode == RESULT_OK) {
            val url = data?.getStringExtra(QRScannerActivity.EXTRA_URL) ?: return
            wirelessController.onScanResult(url)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WirelessTabController.REQ_CAMERA) {
            val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            wirelessController.onCameraPermissionResult(granted)
        }
    }

    /**
     * Enable performance mode for streaming
     * NOTE: setSustainedPerformanceMode is DISABLED - it causes thermal throttling
     * which makes the entire device laggy. Normal power management is more efficient.
     */
    private fun enablePerformanceMode() {
        try {
            // REMOVED: setSustainedPerformanceMode(true)
            // Sustained performance mode forces max CPU/GPU clocks which causes
            // thermal throttling on extended use, making the device laggy.
            // Let the SoC manage power efficiently instead.

            // Use PARTIAL_WAKE_LOCK with timeout to prevent battery drain
            // Screen is already kept on via FLAG_KEEP_SCREEN_ON
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SideKick::PerformanceMode",
                )
            // 30 minute timeout instead of infinite acquire
            wakeLock?.acquire(30 * 60 * 1000L)

            log("🎮 Performance mode ENABLED (balanced)")
        } catch (e: Exception) {
            log("⚠️ Performance mode failed: ${e.message}")
        }
    }

    /**
     * Enable fullscreen immersive mode
     * Uses modern WindowInsets API on Android R+ for better system compatibility
     * Also handles display cutout (notch) to use full screen area
     */
    private fun enableFullscreenMode() {
        // Ensure we draw behind the cutout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    /**
     * Disable fullscreen mode (when disconnected)
     */
    private fun disableFullscreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSurface() {
        binding.surfaceView.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    mainDiag("surfaceCreated")
                    log("Surface created")
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    mainDiag("surfaceChanged: ${width}x$height")
                    log("Surface changed: ${width}x$height")
                    currentSurfaceHolder = holder
                    initializeDecoderForCurrentSurface()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    mainDiag("surfaceDestroyed")
                    log("Surface destroyed")
                    if (!decoderUsingTextureView) {
                        videoDecoder?.release()
                        videoDecoder = null
                    }
                    currentSurfaceHolder = null
                }
            },
        )

        binding.textureView.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    mainDiag("textureAvailable: ${width}x$height")
                    currentTextureSurface = Surface(surface)
                    initializeDecoderForCurrentSurface()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    mainDiag("textureSizeChanged: ${width}x$height")
                    applyTextureTransform()
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    mainDiag("textureDestroyed")
                    if (decoderUsingTextureView) {
                        videoDecoder?.release()
                        videoDecoder = null
                    }
                    currentTextureSurface?.release()
                    currentTextureSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }

        if (binding.textureView.isAvailable && currentTextureSurface == null) {
            binding.textureView.surfaceTexture?.let { currentTextureSurface = Surface(it) }
        }

        binding.surfaceView.setOnTouchListener { view, event ->
            if (demoActive) showDemoTouchHint(event) else handleTouch(view, event)
            true
        }
        binding.textureView.setOnTouchListener { view, event ->
            if (demoActive) showDemoTouchHint(event) else handleTouch(view, event)
            true
        }
    }

    private fun setupUI() {
        binding.connectButton.setOnClickListener {
            var host =
                binding.hostInput.text
                    .toString()
                    .ifEmpty { "127.0.0.1" }
            val port =
                binding.portInput.text
                    .toString()
                    .toIntOrNull() ?: 54321

            // Convert localhost to 127.0.0.1 for better Android compatibility
            if (host.equals("localhost", ignoreCase = true)) {
                host = "127.0.0.1"
            }

            // Validate input
            if (host.isBlank()) {
                showError("Please enter a host address")
                return@setOnClickListener
            }

            updateStatus("Connecting...")
            connect(host, port)
        }

        binding.disconnectButton.setOnClickListener {
            disconnect()
        }

        // Advanced settings toggle
        var advancedVisible = false
        binding.showAdvanced.setOnClickListener {
            advancedVisible = !advancedVisible
            binding.advancedSettings.visibility = if (advancedVisible) View.VISIBLE else View.GONE
            binding.showAdvanced.text = if (advancedVisible) "Hide Advanced Settings" else "Advanced Settings"
        }

        binding.demoButton.setOnClickListener { startDemo() }
        binding.watchVideoButton.setOnClickListener { showHowItWorksVideo() }
        binding.getMacAppLink.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(MAC_APP_URL)))
            } catch (_: Exception) {
                showError("Open $MAC_APP_URL in a browser to download the Mac app.")
            }
        }
        binding.reportProblemLink.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(REPORT_PROBLEM_URL)))
            } catch (_: Exception) {
                showError("Open $REPORT_PROBLEM_URL in a browser to report a problem.")
            }
        }

        // Initial status
        updateStatus("Ready to connect")
    }

    // ==================== Demo Mode ====================

    private fun startDemo() {
        if (demoActive || isConnected) return
        val player =
            try {
                DemoPlayer(applicationContext) { videoDecoder }
            } catch (e: Exception) {
                mainDiag("Demo load failed: ${e.message}")
                showError("Could not start the demo: ${e.message}")
                return
            }
        mainDiag("Demo started")
        demoActive = true
        demoTouchHintShown = false
        // Cancel any in-flight connection attempt (USB connect or wireless
        // auto-reconnect) so its callbacks can't feed real frames into the demo
        // decoder or flip the UI to "connected" mid-demo.
        streamClient?.disconnect()
        streamClient = null
        videoDecoder?.release()
        videoDecoder = null

        displayWidth = DemoPlayer.WIDTH
        displayHeight = DemoPlayer.HEIGHT
        desktopWidth = 0
        desktopHeight = 0
        displayRotation = 0
        displayFlipHorizontal = false
        displayFlipVertical = false

        binding.settingsPanel.visibility = View.GONE
        applySettingsButtonVisibility()
        restoreSettingsButtonPosition()
        enableFullscreenMode()
        applyRotation(0, false, false)
        // The clip is a real 16:10 extended display: letterbox it instead of stretching
        // UI text on phones (a real session sizes the Mac display to the tablet).
        setSurfaceAspect("${DemoPlayer.WIDTH}:${DemoPlayer.HEIGHT}")
        updateResolutionOverlay()
        binding.resolutionText.append(" (demo)")
        binding.connectedMacText.text = "SideKick Demo"
        binding.latencyText.text = "—"
        binding.securityText.text = "Demo"
        binding.securityText.setTextColor(Color.parseColor("#999999"))
        updateOverlayVisibility(prefs.showStatsOverlay)
        initializeDecoderForCurrentSurface()

        player.onStats = { fps, mbps ->
            runOnUiThread {
                binding.fpsText.text = String.format("%.0f FPS", fps)
                binding.bitrateText.text = String.format("%.1f Mbps", mbps)
            }
        }
        demoPlayer = player
        player.start()
        android.widget.Toast
            .makeText(this, "Demo mode — open Settings (⚙) or press Back to exit", android.widget.Toast.LENGTH_LONG)
            .show()
    }

    private fun stopDemo() {
        if (!demoActive) return
        mainDiag("Demo stopped")
        demoPlayer?.stop()
        demoPlayer = null
        demoActive = false
        videoDecoder?.release()
        videoDecoder = null
        displayWidth = 0
        displayHeight = 0

        hideSoftKeyboard()
        binding.overlayRestoreHandle.visibility = View.GONE
        binding.statusBar.visibility = View.GONE
        binding.settingsPanel.visibility = View.VISIBLE
        setSurfaceAspect(null)
        // Recreate the surface so the last demo frame doesn't linger behind the panel
        binding.surfaceView.visibility = View.GONE
        binding.surfaceView.post { binding.surfaceView.visibility = View.VISIBLE }
        disableFullscreenMode()
        resetOrientationToSensor()
    }

    private fun setSurfaceAspect(ratio: String?) {
        val params = binding.surfaceView.layoutParams as ConstraintLayout.LayoutParams
        params.dimensionRatio = ratio
        binding.surfaceView.layoutParams = params
    }

    /** Plays the bundled recording of a real Mac + phone session, looping, full screen. */
    private fun showHowItWorksVideo() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_how_it_works)
        val video = dialog.findViewById<android.widget.VideoView>(R.id.howItWorksVideo)
        video.setVideoURI(android.net.Uri.parse("android.resource://$packageName/${R.raw.how_it_works}"))
        video.setOnPreparedListener { player ->
            player.isLooping = true
            video.start()
        }
        dialog.findViewById<View>(R.id.closeVideoButton).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { video.stopPlayback() }
        dialog.show()
    }

    /** Touch has nothing to control in demo mode — say what it would do instead. */
    private fun showDemoTouchHint(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_UP || demoTouchHintShown) return
        demoTouchHintShown = true
        android.widget.Toast
            .makeText(
                this,
                "Demo mode — when connected, touching here clicks, drags and scrolls on your Mac.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
    }

    private fun showError(message: String) {
        runOnUiThread {
            // connect() fails on a background thread, often seconds after the user rotated
            // the tablet or left the app. By then this Activity is destroyed and Dialog.show()
            // throws BadTokenException (top crash on 1.0.2). The status line already says
            // "Connection failed", so just drop the dialog.
            if (isFinishing || isDestroyed) {
                mainDiag("showError skipped — activity gone: $message")
                return@runOnUiThread
            }
            try {
                android.app.AlertDialog
                    .Builder(this)
                    .setTitle("Connection Error")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: android.view.WindowManager.BadTokenException) {
                mainDiag("showError failed — no window token: ${e.message}")
            }
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            binding.statusText.text = status
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupDraggableOverlay() {
        binding.statusBar.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingOverlay = true
                    overlayDx = view.x - event.rawX
                    overlayDy = view.y - event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingOverlay) {
                        // Calculate new position
                        var newX = event.rawX + overlayDx
                        var newY = event.rawY + overlayDy

                        // Constrain to the parent. The overlay can be wider than a
                        // phone in portrait; OverlayBounds pins it instead of throwing.
                        val parent = view.parent as View
                        newX = OverlayBounds.clamp(newX, parent.width, view.width)
                        newY = OverlayBounds.clamp(newY, parent.height, view.height)

                        view
                            .animate()
                            .x(newX)
                            .y(newY)
                            .setDuration(0)
                            .start()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDraggingOverlay) {
                        // Save position
                        prefs.overlayX = view.x
                        prefs.overlayY = view.y
                        isDraggingOverlay = false
                    }
                    true
                }

                else -> {
                    false
                }
            }
        }
    }

    private fun restoreOverlayPosition() {
        binding.statusBar.translationX = 0f
        binding.statusBar.translationY = 0f

        // Apply opacity and visibility to the unified top overlay
        val opacity = prefs.overlayOpacity
        updateOverlayOpacity(opacity)
        updateOverlayVisibility(prefs.showStatsOverlay)
    }

    private fun updateOverlayOpacity(opacity: Float) {
        binding.statusBar.alpha = opacity
    }

    private fun updateOverlayVisibility(show: Boolean) {
        binding.statsDetailsRow.visibility = if (show) View.VISIBLE else View.GONE
        if (isStreamingUi) {
            applySettingsButtonVisibility()
        } else {
            binding.statusBar.visibility = View.GONE
            binding.overlayRestoreHandle.visibility = View.GONE
        }
    }

    @SuppressLint("InflateParams", "SetTextI18n")
    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = dialog.findViewById<View>(android.R.id.content)
        val showStatsSwitch = view.findViewById<SwitchMaterial>(R.id.showStatsSwitch)
        val hideSettingsSwitch = view.findViewById<SwitchMaterial>(R.id.hideSettingsSwitch)
        val dialogAutoConnectSwitch = view.findViewById<SwitchMaterial>(R.id.dialogAutoConnectSwitch)
        val settingsVersionText = view.findViewById<TextView>(R.id.settingsVersionText)
        val opacitySlider = view.findViewById<Slider>(R.id.opacitySlider)
        val opacityValue = view.findViewById<TextView>(R.id.opacityValue)
        val disconnectButton = view.findViewById<View>(R.id.disconnectSettingsButton)
        val closeButton = view.findViewById<View>(R.id.closeButton)
        val headerCloseButton = view.findViewById<View>(R.id.headerCloseButton)
        val securityBanner = view.findViewById<View>(R.id.connectionSecurityBanner)
        val securityTitle = view.findViewById<TextView>(R.id.connectionSecurityTitle)
        val securitySubtitle = view.findViewById<TextView>(R.id.connectionSecuritySubtitle)

        val activeClient = streamClient
        if (isConnected && !demoActive && activeClient != null && securityBanner != null) {
            securityBanner.visibility = View.VISIBLE
            val currentEntry = pairedHostStorage.load()
            val macLabel = currentEntry?.let { "${it.macName} (${it.host})" } ?: "Mac"
            if (activeClient.isWireless) {
                if (activeClient.isEncrypted) {
                    securityTitle?.text = "🔒 End-to-End Encrypted (AES-256-GCM)"
                    securityTitle?.setTextColor(Color.parseColor("#4ade80"))
                    securitySubtitle?.text = "Connected to $macLabel • Video, touch & keyboard protected"
                } else {
                    securityTitle?.text = "🔓 Unencrypted Wireless Stream"
                    securityTitle?.setTextColor(Color.parseColor("#FFB74D"))
                    securitySubtitle?.text = "Connected to $macLabel • Encryption disabled on Mac"
                }
            } else {
                securityTitle?.text = "🔌 Direct USB Connection"
                securityTitle?.setTextColor(Color.parseColor("#4DD0E1"))
                securitySubtitle?.text = "Local USB cable tunnel"
            }
        } else {
            securityBanner?.visibility = View.GONE
        }

        disconnectButton.visibility = if (isStreamingUi) View.VISIBLE else View.GONE
        if (demoActive) (disconnectButton as TextView).text = "Exit demo"

        // Load current settings
        settingsVersionText?.text = "v${appVersionName()}"
        showStatsSwitch.isChecked = prefs.showStatsOverlay
        hideSettingsSwitch.isChecked = prefs.hideSettingsButton
        dialogAutoConnectSwitch?.isChecked = prefs.autoConnectEnabled
        opacitySlider.value = prefs.overlayOpacity
        opacityValue.text = "${(prefs.overlayOpacity * 100).toInt()}%"

        dialogAutoConnectSwitch?.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoConnectEnabled = isChecked
            binding.autoConnectSwitch.isChecked = isChecked
        }

        showStatsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.showStatsOverlay = isChecked
            updateOverlayVisibility(isChecked)
        }

        hideSettingsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.hideSettingsButton = isChecked
            if (isStreamingUi) {
                applySettingsButtonVisibility()
            }
            if (isChecked) {
                Toast.makeText(
                    this,
                    "Top overlay hidden — tap top handle or use Back to reveal",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        opacitySlider.addOnChangeListener { _, value, _ ->
            prefs.overlayOpacity = value
            updateOverlayOpacity(value)
            updateSettingsButtonOpacity(value)
            opacityValue.text = "${(value * 100).toInt()}%"
        }

        disconnectButton.setOnClickListener {
            dialog.dismiss()
            if (demoActive) stopDemo() else disconnect()
        }

        closeButton.setOnClickListener { dialog.dismiss() }
        headerCloseButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()

        // Size compactly for tablets (max 480dp wide) and wrap height cleanly
        dialog.window?.let { win ->
            val dm = resources.displayMetrics
            val maxW = (480 * dm.density).toInt()
            val targetW = minOf((dm.widthPixels - 48 * dm.density).toInt(), maxW)
            win.setLayout(targetW, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun updateSettingsButtonOpacity(opacity: Float) {
        binding.statusBar.alpha = opacity
    }

    private fun updateKeyboardButtonState() {
        val visible = isConnected && keyboardAllowedOnMac
        binding.keyboardButton.visibility = if (visible) View.VISIBLE else View.GONE
        binding.specialKeysButton.visibility = if (visible) View.VISIBLE else View.GONE
        binding.keyboardButton.alpha = 1.0f
        if (!visible) {
            hideSoftKeyboard()
            binding.specialKeysPanel.visibility = View.GONE
            clearStickyModifiers()
        }
    }

    private fun toggleSpecialKeysPanel() {
        if (!keyboardAllowedOnMac) return
        val show = binding.specialKeysPanel.visibility != View.VISIBLE
        binding.specialKeysPanel.visibility = if (show) View.VISIBLE else View.GONE
        updateSpecialKeysBadge()
    }

    private fun toggleStickyModifier(mask: Int) {
        activeStickyModifiers = activeStickyModifiers xor mask
        updateModifierButtonsUi()
    }

    private fun clearStickyModifiers() {
        if (activeStickyModifiers != 0) {
            activeStickyModifiers = 0
            updateModifierButtonsUi()
        }
    }

    private fun updateModifierButtonsUi() {
        val pairs =
            listOf(
                binding.modCmdBtn to 8,
                binding.modOptBtn to 4,
                binding.modCtrlBtn to 2,
                binding.modShiftBtn to 1,
            )
        for ((btn, mask) in pairs) {
            if ((activeStickyModifiers and mask) != 0) {
                btn.setBackgroundColor(Color.parseColor("#2563EB"))
            } else {
                btn.setBackgroundResource(R.drawable.settings_group_bg)
            }
        }
        updateSpecialKeysBadge()
    }

    private fun updateSpecialKeysBadge() {
        val panelOpen = binding.specialKeysPanel.visibility == View.VISIBLE
        val hasMods = activeStickyModifiers != 0
        binding.specialKeysButtonBadge.setBackgroundResource(
            if (panelOpen || hasMods) R.drawable.overlay_action_bg else R.drawable.overlay_action_bg,
        )
        binding.specialKeysButtonBadge.setTextColor(
            if (hasMods) Color.parseColor("#60A5FA") else Color.WHITE,
        )
    }

    private fun sendSpecialKey(keyCode: Int) {
        if (!isConnected || !keyboardAllowedOnMac) return
        streamClient?.sendKeyPress(keyCode, activeStickyModifiers)
        clearStickyModifiers()
    }

    private fun asciiCharToKeyCode(ch: Char): Int? =
        when (ch.lowercaseChar()) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch.lowercaseChar() - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '/' -> KeyEvent.KEYCODE_SLASH
            '`' -> KeyEvent.KEYCODE_GRAVE
            '\n' -> KeyEvent.KEYCODE_ENTER
            else -> null
        }

    private fun showSwitchMacMenu() {
        val allPaired = pairedHostStorage.loadAll()
        val otherMacs = if (isConnected && !demoActive && allPaired.size > 1) allPaired.drop(1) else emptyList()
        if (otherMacs.isEmpty()) {
            Toast.makeText(this, "No other paired Macs available", Toast.LENGTH_SHORT).show()
            return
        }
        val popup = android.widget.PopupMenu(this, binding.switchMacButton)
        otherMacs.forEachIndexed { idx, mac ->
            popup.menu.add(0, idx, idx, "Switch to ${mac.macName}")
        }
        popup.setOnMenuItemClickListener { item ->
            val target = otherMacs.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            switchWirelessMac(target, fromTakeover = false)
            true
        }
        popup.show()
    }

    private fun toggleSoftKeyboard() {
        if (!keyboardAllowedOnMac) {
            Toast.makeText(this, "Keyboard control is disabled on Mac", Toast.LENGTH_SHORT).show()
            return
        }
        val imeVisible = ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) {
            hideSoftKeyboard()
        } else {
            val proxy = binding.keyboardInputProxy
            proxy.visibility = View.VISIBLE
            proxy.requestFocus()
            proxy.post {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.show(WindowInsets.Type.ime())
                }
                val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.showSoftInput(proxy, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun hideSoftKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.ime())
        }
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.keyboardInputProxy.windowToken, 0)
    }

    private fun setupSettingsButton() {
        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        binding.keyboardButton.setOnClickListener {
            toggleSoftKeyboard()
        }

        binding.specialKeysButton.setOnClickListener {
            toggleSpecialKeysPanel()
        }

        binding.modCmdBtn.setOnClickListener { toggleStickyModifier(8) }
        binding.modOptBtn.setOnClickListener { toggleStickyModifier(4) }
        binding.modCtrlBtn.setOnClickListener { toggleStickyModifier(2) }
        binding.modShiftBtn.setOnClickListener { toggleStickyModifier(1) }

        binding.keyEscBtn.setOnClickListener { sendSpecialKey(KeyEvent.KEYCODE_ESCAPE) }
        binding.keyTabBtn.setOnClickListener { sendSpecialKey(KeyEvent.KEYCODE_TAB) }
        binding.keyDelBtn.setOnClickListener { sendSpecialKey(KeyEvent.KEYCODE_FORWARD_DEL) }
        binding.keyUpBtn.setOnClickListener { sendSpecialKey(KeyEvent.KEYCODE_DPAD_UP) }
        binding.keyDownBtn.setOnClickListener { sendSpecialKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        binding.keyLeftBtn.setOnClickListener { sendSpecialKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        binding.keyRightBtn.setOnClickListener { sendSpecialKey(KeyEvent.KEYCODE_DPAD_RIGHT) }

        binding.shortcutSpotlightBtn.setOnClickListener {
            if (isConnected && keyboardAllowedOnMac) {
                streamClient?.sendKeyPress(KeyEvent.KEYCODE_SPACE, 8)
            }
        }
        binding.shortcutAppSwitchBtn.setOnClickListener {
            if (isConnected && keyboardAllowedOnMac) {
                streamClient?.sendKeyPress(KeyEvent.KEYCODE_TAB, 8)
            }
        }
        binding.shortcutForceQuitBtn.setOnClickListener {
            if (isConnected && keyboardAllowedOnMac) {
                streamClient?.sendKeyPress(KeyEvent.KEYCODE_ESCAPE, 8 or 4)
            }
        }

        binding.switchMacButton.setOnClickListener {
            showSwitchMacMenu()
        }

        binding.hideOverlayButton.setOnClickListener {
            binding.statusBar.visibility = View.GONE
            binding.specialKeysPanel.visibility = View.GONE
            binding.overlayRestoreHandle.visibility = View.VISIBLE
            revealHandler.removeCallbacks(hideSettingsButtonRunnable)
        }

        binding.overlayRestoreHandle.setOnClickListener {
            if (prefs.hideSettingsButton) {
                revealSettingsButtonTemporarily()
            } else {
                binding.statusBar.visibility = View.VISIBLE
                binding.overlayRestoreHandle.visibility = View.GONE
            }
        }

        binding.overlayDisconnectButton.setOnClickListener {
            if (demoActive) stopDemo() else disconnect()
        }

        binding.keyboardInputProxy.onTextCommitted = { text ->
            if (isConnected && keyboardAllowedOnMac) {
                if (activeStickyModifiers != 0 && text.length == 1) {
                    val keyCode = asciiCharToKeyCode(text[0])
                    if (keyCode != null) {
                        streamClient?.sendKeyPress(keyCode, activeStickyModifiers)
                        clearStickyModifiers()
                    } else {
                        streamClient?.sendKeyboardText(text)
                    }
                } else {
                    streamClient?.sendKeyboardText(text)
                }
            }
        }
        binding.keyboardInputProxy.onBackspace = { count ->
            if (isConnected && keyboardAllowedOnMac) {
                streamClient?.sendKeyboardBackspace(count)
            }
        }
        binding.keyboardInputProxy.onKeyEventForwarded = { event ->
            handleForwardedKeyEvent(event)
        }

        // Escape hatch for the hidden overlay: the back gesture reveals it
        // instead of leaving the app. Back is not forwarded to the Mac.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isStreamingUi && binding.statusBar.visibility != View.VISIBLE) {
                        if (prefs.hideSettingsButton) {
                            revealSettingsButtonTemporarily()
                        } else {
                            binding.statusBar.visibility = View.VISIBLE
                            binding.overlayRestoreHandle.visibility = View.GONE
                        }
                    } else if (demoActive) {
                        stopDemo()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
    }

    /** Streaming-time visibility of the unified top overlay, honoring the hide preference. */
    private fun applySettingsButtonVisibility() {
        val showBar = isStreamingUi && !prefs.hideSettingsButton
        binding.statusBar.visibility = if (showBar) View.VISIBLE else View.GONE
        binding.overlayRestoreHandle.visibility = if (isStreamingUi && !showBar) View.VISIBLE else View.GONE
        binding.switchMacButton.visibility =
            if (isConnected && !demoActive && pairedHostStorage.loadAll().size > 1) View.VISIBLE else View.GONE
        updateKeyboardButtonState()
    }

    private val revealHandler = Handler(Looper.getMainLooper())
    private val hideSettingsButtonRunnable =
        Runnable {
            if (isStreamingUi && prefs.hideSettingsButton) {
                binding.statusBar.visibility = View.GONE
                binding.specialKeysPanel.visibility = View.GONE
                binding.overlayRestoreHandle.visibility = View.VISIBLE
            }
        }

    private fun revealSettingsButtonTemporarily() {
        binding.statusBar.visibility = View.VISIBLE
        binding.overlayRestoreHandle.visibility = View.GONE
        revealHandler.removeCallbacks(hideSettingsButtonRunnable)
        revealHandler.postDelayed(hideSettingsButtonRunnable, 5_000L)
    }

    private fun restoreSettingsButtonPosition() {
        updateSettingsButtonPosition(prefs.settingsButtonCorner)
    }

    private fun updateSettingsButtonPosition(
        @Suppress("UNUSED_PARAMETER") position: Int,
    ) {
        binding.statusBar.translationX = 0f
        binding.statusBar.translationY = 0f
    }

    /**
     * Display config from a new Mac always arrives AFTER codecSelected, so a
     * missing negotiation at this point proves the Mac app predates H.264
     * support — surface that instead of a silent black screen.
     */
    private fun warnIfAvcOnlyWithoutNegotiation() {
        if (!CodecCapabilities.hasHevcDecoder && streamClient?.codecNegotiated != true) {
            mainDiag("AVC-only device but Mac did not negotiate codec — Mac app too old")
            runOnUiThread {
                updateStatus("This device has no HEVC decoder. Update the SideKick Mac app to enable H.264 support.")
            }
        }
    }

    /**
     * Recreate the decoder when the negotiated stream codec doesn't match the
     * decoder's mime. Display config and codecSelected can arrive in either
     * order on reconnect; without this, a decoder created with the default
     * HEVC mime keeps consuming the H.264 stream and never outputs a frame —
     * a permanent black screen on AVC-only devices (e.g. Unisoc tablets).
     */
    private fun onStreamCodecSelected(isHevc: Boolean) {
        val expectedMime =
            if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        runOnUiThread {
            val dec = videoDecoder
            when {
                dec == null -> {
                    mainDiag("Codec selected ($expectedMime) — initializing deferred decoder")
                    initializeDecoderForCurrentSurface()
                }
                dec.mime != expectedMime -> {
                    mainDiag("Stream codec is $expectedMime but decoder is ${dec.mime} — recreating")
                    dec.release()
                    videoDecoder = null
                    initializeDecoderForCurrentSurface()
                }
            }
        }
    }

    /**
     * Stream size first, since that is what the decoder and every other stat in this overlay
     * describe. The desktop size is appended only when it differs, so a scaled HiDPI desktop is
     * visible rather than silently standing in for the resolution actually being sent.
     */
    private fun updateResolutionOverlay() {
        if (displayWidth <= 0 || displayHeight <= 0) return
        val stream = "${displayWidth}x$displayHeight"
        val differs = desktopWidth > 0 && (desktopWidth != displayWidth || desktopHeight != displayHeight)
        binding.resolutionText.text =
            if (differs) "$stream (desktop ${desktopWidth}x$desktopHeight)" else stream
    }

    private fun shouldUseTextureView(): Boolean = displayFlipHorizontal || displayFlipVertical

    private fun activeVideoSurface(): Pair<Surface, Boolean>? {
        return if (shouldUseTextureView()) {
            currentTextureSurface?.takeIf { it.isValid }?.let { it to true }
        } else {
            currentSurfaceHolder?.surface?.takeIf { it.isValid }?.let { it to false }
        }
    }

    private fun initializeDecoderForCurrentSurface() {
        if (displayWidth <= 0 || displayHeight <= 0) {
            mainDiag("initializeDecoder skipped — no display config yet")
            return
        }
        // AVC-only device: an HEVC decoder can never decode the H.264 stream
        // the Mac will send — defer until codecSelected arrives, then
        // onStreamCodecSelected initializes with the correct mime.
        if (!demoActive && !CodecCapabilities.hasHevcDecoder && streamClient?.codecNegotiated != true) {
            mainDiag("initializeDecoder deferred — AVC-only device awaiting codec negotiation")
            return
        }

        val (surface, useTextureView) =
            activeVideoSurface() ?: run {
                val kind = if (shouldUseTextureView()) "TextureView" else "SurfaceView"
                mainDiag("initializeDecoder skipped — no valid $kind surface")
                return
            }

        if (videoDecoder != null && decoderUsingTextureView == useTextureView) {
            try {
                videoDecoder?.updateResolution(displayWidth, displayHeight)
                return
            } catch (e: Exception) {
                // Restarting the codec in place failed (crash on 1.0.1): build a fresh
                // decoder below instead, which reports failure in the UI rather than crashing.
                mainDiag("Decoder resize FAILED: ${e.message} — recreating")
            }
        }

        videoDecoder?.release()
        videoDecoder = null
        decoderUsingTextureView = useTextureView

        mainDiag(
            "initializeDecoder called, surface=$surface, valid=${surface.isValid}, " +
                "res=${displayWidth}x$displayHeight, texture=$useTextureView",
        )
        try {
            val displayObj =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
            val mime =
                if (demoActive || streamClient?.streamCodecIsHevc == false) {
                    MediaFormat.MIMETYPE_VIDEO_AVC
                } else {
                    MediaFormat.MIMETYPE_VIDEO_HEVC
                }
            videoDecoder = VideoDecoder(surface, displayObj, displayWidth, displayHeight, mime)
            videoDecoder?.onFrameDecoded = { buffer ->
                streamClient?.releaseBuffer(buffer)
            }
            videoDecoder?.onKeyframeRequired = { force, reason ->
                streamClient?.requestKeyframe(force = force, reason = reason)
            }
            videoDecoder?.onDecoderStalled = {
                // Black screen with live stats: tell the user why instead of
                // staying silent (issue #41). Toast renders above the (black)
                // SurfaceView; the settings panel is hidden while streaming.
                // The sustainable size, not the decoder's advertised one — that is typically far
                // above anything it can really output, which made this message actively misleading.
                val panel = PanelGeometry.of(displayObj)
                val cap =
                    panel?.let {
                        CodecCapabilities.maxStreamSize(
                            mime,
                            it.width,
                            it.height,
                            CodecCapabilities.REFERENCE_FPS,
                        )
                    }
                runOnUiThread {
                    val capText = cap?.let { " (max ~${it.first}×${it.second})" } ?: ""
                    android.widget.Toast
                        .makeText(
                            this,
                            "No video output — the stream resolution may exceed " +
                                "this tablet's decoder limit$capText. " +
                                "Lower the resolution or refresh rate, or disable HiDPI, on the Mac.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                }
            }
            streamClient?.requestKeyframe(force = true, reason = "decoder initialized")
            mainDiag("Decoder initialized OK ${displayWidth}x$displayHeight mime=$mime, texture=$useTextureView")
            log("✅ Decoder initialized ${displayWidth}x$displayHeight $mime (${displayObj?.refreshRate ?: 60f}Hz)")
        } catch (e: Exception) {
            decoderUsingTextureView = false
            mainDiag("Decoder init FAILED: ${e.message}")
            log("❌ Failed to initialize decoder: ${e.message}")
            runOnUiThread {
                updateStatus("Video decoder failed: ${e.message}")
                // The status text sits in the settings panel, hidden while streaming —
                // without this the user would just see a black screen.
                if (isStreamingUi) {
                    android.widget.Toast
                        .makeText(
                            this,
                            "Video decoder failed — disconnect and reconnect to retry.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }
    }

    /**
     * Wire up all StreamClient callbacks. Used by both USB connect() and wireless connectWireless().
     */
    private fun setupStreamClientCallbacks() {
        streamClient?.onFrameReceived = { frameData, frameSize, timestamp, isKeyframe ->
            val dec = videoDecoder
            if (dec != null) {
                dec.decode(frameData, frameSize, timestamp, isKeyframe)
            } else {
                mainDiag("FRAME DROPPED: videoDecoder is null!")
            }
        }

        videoDecoder?.onFrameDecoded = { buffer ->
            streamClient?.releaseBuffer(buffer)
        }

        streamClient?.onLatencyMeasured = { rttMs ->
            runOnUiThread {
                binding.latencyText.text = String.format("%.1f ms", rttMs)
            }
        }

        streamClient?.onKeyboardPermissionChanged = { allowed ->
            runOnUiThread {
                keyboardAllowedOnMac = allowed
                updateKeyboardButtonState()
            }
        }

        streamClient?.onConnectionStatus = { connected ->
            runOnUiThread {
                isConnected = connected
                reviewPrompter.onConnectionChanged(connected)
                val encrypted = streamClient?.isEncrypted == true
                val wireless = streamClient?.isWireless == true
                if (connected) {
                    if (wireless) {
                        updateStatus(
                            if (encrypted) {
                                "Connected • 🔒 Encrypted (AES-256-GCM)"
                            } else {
                                "Connected • 🔓 Unencrypted"
                            },
                        )
                    } else {
                        updateStatus("Connected via USB - Streaming active")
                    }
                } else {
                    updateStatus("Disconnected")
                }
                binding.connectButton.isEnabled = !connected
                binding.disconnectButton.isEnabled = connected
                binding.statusIndicator.setBackgroundResource(
                    if (connected) android.R.color.holo_green_light else android.R.color.holo_red_light,
                )
                if (connected) {
                    if (wireless) {
                        if (encrypted) {
                            binding.securityText.text = "🔒 AES-256"
                            binding.securityText.setTextColor(Color.parseColor("#4CAF50"))
                        } else {
                            binding.securityText.text = "🔓 Off"
                            binding.securityText.setTextColor(Color.parseColor("#FF9800"))
                        }
                    } else {
                        binding.securityText.text = "🔌 USB"
                        binding.securityText.setTextColor(Color.parseColor("#00BCD4"))
                    }
                    startPingTimer()
                    try {
                        startService(Intent(this@MainActivity, DisconnectOnTaskRemovedService::class.java))
                    } catch (_: Exception) {
                    }
                    stopChecklistUpdates()
                    enableFullscreenMode()
                    binding.settingsPanel.visibility = View.GONE
                    applySettingsButtonVisibility()
                    restoreSettingsButtonPosition()
                    updateOverlayVisibility(prefs.showStatsOverlay)
                    // For wireless mode, transition controller to CONNECTED here —
                    // not in MainActivity.connectWireless's coroutine after the
                    // receive loop returns (that runs AFTER disconnect, causing
                    // a stale CONNECTED transition that hides the PAIRED_IDLE UI).
                    if (prefs.connectionMode == ConnectionMode.WIRELESS) {
                        val entry = pairedHostStorage.load()
                        val macName = entry?.macName ?: "Mac"
                        wirelessController.onConnectSuccess(
                            macName,
                            entry?.host ?: "—",
                            encrypted = encrypted,
                        )
                        val encToast =
                            if (encrypted) {
                                "🔒 Encrypted (AES-256-GCM)"
                            } else {
                                "🔓 Unencrypted"
                            }
                        android.widget.Toast
                            .makeText(
                                this,
                                "Connected to $macName • $encToast",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                    }
                } else {
                    stopPingTimer()
                    disableFullscreenMode()
                    resetOrientationToSensor()
                    binding.settingsPanel.visibility = View.VISIBLE
                    hideSoftKeyboard()
        binding.overlayRestoreHandle.visibility = View.GONE
                    binding.statusBar.visibility = View.GONE
                    val mode = prefs.connectionMode
                    val willTransition = mode == ConnectionMode.WIRELESS
                    android.util.Log.i(
                        "MainActivity",
                        "onConnectionStatus(false) — mode=$mode, willTransition=$willTransition",
                    )
                    if (mode == ConnectionMode.WIRELESS) {
                        // Don't restart checklist (it conflicts with wireless on Mac).
                        // Tell wireless controller to show the idle/reconnect UI.
                        wirelessController.onStreamDisconnected()
                    } else {
                        log("📋 Restarting checklist updates")
                        startChecklistUpdates()
                    }
                    clearVideoSurface()
                }
            }
        }

        streamClient?.onDesktopSize = { w, h ->
            desktopWidth = w
            desktopHeight = h
            runOnUiThread { updateResolutionOverlay() }
        }

        streamClient?.onCodecSelected = { isHevc -> onStreamCodecSelected(isHevc) }

        streamClient?.onDisplaySize = { width, height, rotation, flipHorizontal, flipVertical ->
            mainDiag("onDisplaySize: ${width}x$height @ $rotation°, h=$flipHorizontal, v=$flipVertical")
            warnIfAvcOnlyWithoutNegotiation()
            displayWidth = width
            displayHeight = height
            displayRotation = rotation
            displayFlipHorizontal = flipHorizontal
            displayFlipVertical = flipVertical
            runOnUiThread {
                updateResolutionOverlay()
                applyRotation(rotation, flipHorizontal, flipVertical)
                initializeDecoderForCurrentSurface()
            }
            log("Display: ${width}x$height @ $rotation°")
        }

        streamClient?.onStats = { fps, mbps ->
            runOnUiThread {
                binding.fpsText.text = String.format("%.0f FPS", fps)
                binding.bitrateText.text = String.format("%.1f Mbps", mbps)
            }
        }
    }

    /**
     * Code pairing (issue #35): manual host/port/code entry for tablets without
     * a camera. On success the Mac issues the real token and we connect through
     * the same path as a QR scan.
     */
    private fun showCodePairingDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_code_pairing, null)
        val hostField = view.findViewById<android.widget.EditText>(R.id.codePairHost)
        val portField = view.findViewById<android.widget.EditText>(R.id.codePairPort)
        val codeField = view.findViewById<android.widget.EditText>(R.id.codePairCode)
        val errorText = view.findViewById<android.widget.TextView>(R.id.codePairError)

        // Re-pair convenience: prefill the last paired Mac so only the fresh
        // code needs typing.
        pairedHostStorage.load()?.let { cached ->
            hostField.setText(cached.host)
            portField.setText(cached.port.toString())
        }

        val dialog =
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Pair with code")
                .setView(view)
                .setPositiveButton("Pair", null) // listener installed below so it doesn't auto-dismiss
                .setNegativeButton("Cancel", null)
                .create()

        dialog.setOnShowListener {
            val pairButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            pairButton.setOnClickListener {
                val host = hostField.text.toString().trim()
                val port = portField.text.toString().trim().toIntOrNull()
                val code = codeField.text.toString().filter { it.isDigit() }

                errorText.visibility = View.GONE
                when {
                    host.isEmpty() -> {
                        errorText.text = "Enter the Mac's address (shown as \"Listening\" under the QR)."
                        errorText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    port == null || port !in 1..65535 -> {
                        errorText.text = "Port must be between 1 and 65535."
                        errorText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    code.length != AuthHandshake.PAIR_CODE_LEN -> {
                        errorText.text = "The code is ${AuthHandshake.PAIR_CODE_LEN} digits."
                        errorText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                }
                pairButton.isEnabled = false
                pairButton.text = "Pairing…"

                fun showPairError(message: String) {
                    runOnUiThread {
                        pairButton.isEnabled = true
                        pairButton.text = "Pair"
                        errorText.text = message
                        errorText.visibility = View.VISIBLE
                    }
                }

                val deviceName = WirelessTabController.defaultDeviceName(this@MainActivity)
                pairingJob =
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val client = StreamClient(host, port!!, applicationContext)
                            val result = client.pairWithCode(code, deviceName)
                            // Dialog dismissed mid-flight (Cancel/back): drop the
                            // token instead of silently connecting.
                            ensureActive()
                            runOnUiThread {
                                dialog.dismiss()
                                wirelessController.onCodePairComplete(host, port, result.token, result.macName)
                            }
                        } catch (e: StreamClient.PairingError) {
                            showPairError(
                                when (e) {
                                    is StreamClient.PairingError.CodeRejected ->
                                        "Code not accepted. Codes change after each use — " +
                                            "check the Mac for the current one."
                                    is StreamClient.PairingError.HostTooOld ->
                                        "Update SideKick on the Mac to use code pairing."
                                },
                            )
                        } catch (e: StreamClient.WirelessConnectError) {
                            showPairError(
                                when (e) {
                                    is StreamClient.WirelessConnectError.NetworkUnreachable ->
                                        "No response from $host:$port. Check both devices are " +
                                            "on the same WiFi and the Mac app is running."
                                    else -> "Connection error — try again."
                                },
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("Code pairing failed unexpectedly: ${e.message}")
                            showPairError("Pairing failed — try again.")
                        }
                    }
            }
        }
        dialog.setOnDismissListener {
            pairingJob?.cancel()
            pairingJob = null
        }
        dialog.show()
    }

    private fun connectWireless(
        host: String,
        port: Int,
        token: ByteArray,
        deviceName: String,
        macName: String,
    ) {
        stopDemo() // a real connection (incl. wireless auto-reconnect) takes over the decoder
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("Connecting wirelessly to $host:$port...")
                runOnUiThread { binding.connectedMacText.text = macName }
                streamClient = StreamClient(host, port, applicationContext)
                setupStreamClientCallbacks()
                streamClient?.connectWireless(token, deviceName)
                // NOTE: onConnectSuccess is fired from the onConnectionStatus(true)
                // listener (above) right after handshake OK — not here. This line
                // would otherwise run AFTER the receive loop exits, i.e. AFTER
                // disconnect, incorrectly transitioning back to CONNECTED.
            } catch (e: StreamClient.WirelessConnectError) {
                runOnUiThread {
                    wirelessController.onConnectError(e)
                }
            } catch (e: Exception) {
                log("Wireless connect failed: ${e.message}")
                runOnUiThread {
                    wirelessController.onConnectError(StreamClient.WirelessConnectError.NetworkUnreachable)
                }
            }
        }
    }

    private fun connect(
        host: String,
        port: Int,
    ) {
        stopDemo()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("Connecting to $host:$port...")
                runOnUiThread { binding.connectedMacText.text = "USB Mac" }

                streamClient = StreamClient(host, port, applicationContext)
                streamClient?.onFrameReceived = { frameData, frameSize, timestamp, isKeyframe ->
                    val dec = videoDecoder
                    if (dec != null) {
                        dec.decode(frameData, frameSize, timestamp, isKeyframe)
                    } else {
                        streamClient?.releaseBuffer(frameData)
                    }
                }

                // Wire up buffer release callback for buffer pooling
                // When decode completes, buffer is returned to StreamClient's pool
                videoDecoder?.onFrameDecoded = { buffer ->
                    streamClient?.releaseBuffer(buffer)
                }
                videoDecoder?.onKeyframeRequired = { force, reason ->
                    streamClient?.requestKeyframe(force = force, reason = reason)
                }

                // Latency measurement via ping/pong
                streamClient?.onLatencyMeasured = { rttMs ->
                    runOnUiThread {
                        binding.latencyText.text = String.format("%.1f ms", rttMs)
                    }
                }

                streamClient?.onKeyboardPermissionChanged = { allowed ->
                    runOnUiThread {
                        keyboardAllowedOnMac = allowed
                        updateKeyboardButtonState()
                    }
                }

                streamClient?.onConnectionStatus = { connected ->
                    runOnUiThread {
                        // Update connection state flag
                        isConnected = connected
                        reviewPrompter.onConnectionChanged(connected)

                        if (connected) {
                            updateStatus("Connected - Streaming active")
                        } else {
                            updateStatus("Disconnected")
                        }

                        binding.connectButton.isEnabled = !connected
                        binding.disconnectButton.isEnabled = connected

                        // Update status indicator color
                        binding.statusIndicator.setBackgroundResource(
                            if (connected) {
                                android.R.color.holo_green_light
                            } else {
                                android.R.color.holo_red_light
                            },
                        )

                        if (connected) {
                            binding.securityText.text = "🔌 USB"
                            binding.securityText.setTextColor(Color.parseColor("#00BCD4"))
                            // Start periodic ping for latency measurement
                            startPingTimer()
                    try {
                        startService(Intent(this@MainActivity, DisconnectOnTaskRemovedService::class.java))
                    } catch (_: Exception) {
                    }

                            // Stop checklist updates when connected (prevents socket conflicts)
                            stopChecklistUpdates()

                            // Enter fullscreen mode when connected
                            enableFullscreenMode()

                            binding.settingsPanel.visibility = View.GONE
                            applySettingsButtonVisibility()
                            restoreSettingsButtonPosition()
                            updateOverlayVisibility(prefs.showStatsOverlay)
                        } else {
                            // Stop ping timer
                            stopPingTimer()

                            // Exit fullscreen mode when disconnected
                            disableFullscreenMode()

                            // Reset to follow device sensor when disconnected
                            resetOrientationToSensor()

                            binding.settingsPanel.visibility = View.VISIBLE
                            hideSoftKeyboard()
                            binding.overlayRestoreHandle.visibility = View.GONE
                            binding.statusBar.visibility = View.GONE
                            clearVideoSurface()

                            // Restart checklist updates immediately
                            log("📋 Restarting checklist updates")
                            startChecklistUpdates()
                        }
                    }
                }

                streamClient?.onCodecSelected = { isHevc -> onStreamCodecSelected(isHevc) }

                streamClient?.onDesktopSize = { w, h ->
                    desktopWidth = w
                    desktopHeight = h
                    runOnUiThread { updateResolutionOverlay() }
                }

                streamClient?.onDisplaySize = { width, height, rotation, flipHorizontal, flipVertical ->
                    mainDiag("onDisplaySize: ${width}x$height @ $rotation°, h=$flipHorizontal, v=$flipVertical")
                    warnIfAvcOnlyWithoutNegotiation()
                    displayWidth = width
                    displayHeight = height
                    displayRotation = rotation
                    displayFlipHorizontal = flipHorizontal
                    displayFlipVertical = flipVertical

                    runOnUiThread {
                        updateResolutionOverlay()
                        applyRotation(rotation, flipHorizontal, flipVertical)
                        initializeDecoderForCurrentSurface()
                    }
                    log("Display: ${width}x$height @ $rotation°")
                }

                streamClient?.onStats = { fps, mbps ->
                    runOnUiThread {
                        binding.fpsText.text = String.format("%.0f FPS", fps)
                        binding.bitrateText.text = String.format("%.1f Mbps", mbps)
                    }
                }

                streamClient?.connect()
            } catch (e: Exception) {
                val errorMessage =
                    when {
                        e.message?.contains("ECONNREFUSED") == true -> {
                            "Mac server is not running.\n\nPlease start SideKick.app on your Mac first."
                        }

                        e.message?.contains("Network is unreachable") == true -> {
                            "Cannot reach Mac.\n\n" +
                                "Make sure both devices are connected via USB cable and ADB reverse is configured."
                        }

                        e.message?.contains("timeout") == true -> {
                            "Connection timeout.\n\nCheck if Mac firewall is blocking port $port."
                        }

                        else -> {
                            "Connection failed: ${e.message}\n\n" +
                                "Try:\n• Start SideKick.app on Mac\n" +
                                "• Check USB connection\n• Run: adb reverse tcp:$port tcp:$port"
                        }
                    }
                updateStatus("Connection failed")
                showError(errorMessage)
            }
        }
    }

    private fun disconnect() {
        stopPingTimer()
        try {
            stopService(Intent(this, DisconnectOnTaskRemovedService::class.java))
        } catch (_: Exception) {
        }
        streamClient?.disconnect()
        // Reset display config so next connect defers decoder init until config arrives
        displayWidth = 0
        displayHeight = 0
        desktopWidth = 0
        desktopHeight = 0
        displayFlipHorizontal = false
        displayFlipVertical = false
        runOnUiThread {
            clearVideoSurface()
        }
        log("Disconnected")
    }

    private fun clearVideoSurface() {
        setSurfaceAspect(null)
        binding.textureView.visibility = View.GONE
        applyTextureTransform()
        // Recreate the SurfaceView surface so the last Mac desktop frame doesn't linger behind the home card
        binding.surfaceView.visibility = View.GONE
        binding.surfaceView.post {
            if (!isConnected && !demoActive) {
                binding.surfaceView.visibility = View.VISIBLE
            }
        }
    }

    private fun startPingTimer() {
        stopPingTimer()
        pingJob =
            lifecycleScope.launch(Dispatchers.IO) {
                while (true) {
                    kotlinx.coroutines.delay(1000) // Ping every 1 second
                    streamClient?.sendPing()
                }
            }
    }

    private fun stopPingTimer() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun cleanup() {
        try {
            handoverListener?.stop()
            handoverListener = null
            lanHostDiscovery.stop()
            stopDemo()
            disconnect()
            videoDecoder?.release()
            videoDecoder = null
            currentTextureSurface?.release()
            currentTextureSurface = null

            // Release wake lock safely
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (e: Exception) {
                // Ignore wake lock release errors
            }
            wakeLock = null
            log("🎮 Performance mode DISABLED")
        } catch (e: Exception) {
            log("⚠️ Cleanup error: ${e.message}")
        }
    }

    private fun handleTouch(
        view: View,
        event: MotionEvent,
    ) {
        val rawX = event.x / view.width.toFloat()
        val rawY = event.y / view.height.toFloat()
        val x = if (displayFlipHorizontal) 1f - rawX else rawX
        val y = if (displayFlipVertical) 1f - rawY else rawY
        val pointerCount = event.pointerCount.coerceAtMost(2)

        var x2 = 0f
        var y2 = 0f
        if (pointerCount >= 2) {
            val rawX2 = event.getX(1) / view.width.toFloat()
            val rawY2 = event.getY(1) / view.height.toFloat()
            x2 = if (displayFlipHorizontal) 1f - rawX2 else rawX2
            y2 = if (displayFlipVertical) 1f - rawY2 else rawY2
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inputPredictor.reset()
                inputPredictor.addSample(x, y)
                streamClient?.sendTouch(x, y, 0, pointerCount, x2, y2)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                streamClient?.sendTouch(x, y, 0, pointerCount, x2, y2)
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1) {
                    inputPredictor.addSample(x, y)
                    val (px, py) = inputPredictor.predictPosition(12f)
                    streamClient?.sendTouch(px, py, 1, 1)
                } else {
                    streamClient?.sendTouch(x, y, 1, pointerCount, x2, y2)
                }
            }

            MotionEvent.ACTION_UP -> {
                inputPredictor.reset()
                streamClient?.sendTouch(x, y, 2, 1)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                streamClient?.sendTouch(x, y, 2, pointerCount, x2, y2)
            }

            MotionEvent.ACTION_CANCEL -> {
                inputPredictor.reset()
                streamClient?.sendTouch(x, y, 2, 1)
            }
        }
    }

    private fun applyRotation(
        rotation: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
    ) {
        requestedOrientation =
            when (rotation) {
                90 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }

        binding.surfaceView.rotation = 0f
        binding.surfaceView.visibility = View.VISIBLE
        binding.textureView.visibility = if (flipHorizontal || flipVertical) View.VISIBLE else View.GONE
        applyTextureTransform()

        log(
            "🔄 Orientation: ${when (rotation) {
                90 -> "Portrait"
                180 -> "Landscape (flipped)"
                270 -> "Portrait (flipped)"
                else -> "Landscape"
            }}${if (flipHorizontal || flipVertical) " mirrored" else ""}",
        )
    }

    private fun applyTextureTransform() {
        val view = binding.textureView
        val matrix = Matrix()
        val centerX = view.width / 2f
        val centerY = view.height / 2f
        matrix.postScale(
            if (displayFlipHorizontal) -1f else 1f,
            if (displayFlipVertical) -1f else 1f,
            centerX,
            centerY,
        )
        view.setTransform(matrix)
    }

    /**
     * Reset orientation to follow device sensor (when disconnected)
     */
    private fun resetOrientationToSensor() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    private fun log(message: String) {
        runOnUiThread {
            val current = binding.logText.text.toString()
            val lines = current.split("\n").takeLast(5)
            binding.logText.text = (lines + message).joinToString("\n")
        }
    }

    override fun onResume() {
        super.onResume()
        // Only nudge about updates while idle — never over a live stream or the demo.
        if (!isStreamingUi) appUpdater.checkForUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopChecklistUpdates()
        cleanup()
    }

    // ==================== Connection Checklist ====================

    private fun startChecklistUpdates() {
        // Stop any existing runnable first to prevent duplicates
        checklistRunnable?.let {
            checklistHandler.removeCallbacks(it)
        }

        checklistRunnable =
            object : Runnable {
                override fun run() {
                    updateChecklist()
                    checklistHandler.postDelayed(this, 2000) // Update every 2 seconds
                }
            }
        checklistHandler.post(checklistRunnable!!)
    }

    private fun stopChecklistUpdates() {
        checklistRunnable?.let {
            checklistHandler.removeCallbacks(it)
            checklistRunnable = null
        }
    }

    private fun updateChecklist() {
        // Skip if connected (to prevent socket conflicts)
        if (isConnected) return

        // Check Developer Mode (if we can run this app with USB debugging, dev mode is enabled)
        val isDeveloperModeEnabled =
            Settings.Secure.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0,
            ) == 1
        updateChecklistItem(binding.checkDeveloperMode, isDeveloperModeEnabled)

        // Check USB Debugging (ADB enabled)
        val isAdbEnabled =
            Settings.Secure.getInt(
                contentResolver,
                Settings.Global.ADB_ENABLED,
                0,
            ) == 1
        updateChecklistItem(binding.checkUsbDebugging, isAdbEnabled)

        // Check USB connected (check if any USB device is connected)
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val isUsbConnected = usbManager.deviceList.isNotEmpty() || isCharging()
        updateChecklistItem(binding.checkUsbConnected, isUsbConnected)

        // Check Mac Server (try to connect to port)
        lifecycleScope.launch(Dispatchers.IO) {
            // Double-check connection state before socket test
            if (isConnected) return@launch

            val port =
                binding.portInput.text
                    .toString()
                    .toIntOrNull() ?: 54321
            val isServerRunning = checkServerRunning("127.0.0.1", port)
            runOnUiThread {
                // Final check before updating UI
                if (isConnected) return@runOnUiThread

                updateChecklistItem(binding.checkMacServer, isServerRunning)

                // Update main status indicator based on all checklist items
                val allReady = isDeveloperModeEnabled && isAdbEnabled && isUsbConnected && isServerRunning
                updateMainStatus(allReady)
            }
        }
    }

    private fun updateMainStatus(allReady: Boolean) {
        binding.statusIndicator.setBackgroundResource(
            if (allReady) {
                R.drawable.status_indicator_green
            } else {
                R.drawable.status_indicator_red
            },
        )
        binding.statusText.text = if (allReady) "Ready to connect" else "Not ready to connect"
    }

    private fun updateChecklistItem(
        indicator: View,
        isOk: Boolean,
    ) {
        indicator.setBackgroundResource(
            if (isOk) {
                R.drawable.status_indicator_green
            } else {
                R.drawable.status_indicator_red
            },
        )
    }

    private fun isCharging(): Boolean {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, intentFilter)
        val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Check if Mac server is actually running (not just ADB reverse)
     *
     * Problem: When `adb reverse tcp:8888 tcp:8888` is active, ADB daemon listens on port 8888.
     * A simple socket connect will succeed to ADB daemon, not the actual Mac server.
     *
     * Solution: After connecting, try to read data with a short timeout.
     * Mac server sends display config (type=1) immediately upon connection.
     * ADB daemon doesn't send anything, so read will timeout → false.
     */
    private fun checkServerRunning(
        host: String,
        port: Int,
    ): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 300) // 300ms connect timeout
            socket.soTimeout = 200 // 200ms read timeout

            // Try to read - Mac server sends display config immediately
            // ADB daemon doesn't send anything, so read will timeout
            val input = socket.getInputStream()
            val firstByte = input.read() // Blocks up to soTimeout

            // If we got data (>= 0), it's the real Mac server
            // -1 means EOF (connection closed), anything else is data
            firstByte >= 0
        } catch (e: Exception) {
            // Timeout, connection refused, or other error = server not running
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isConnected && binding.settingsPanel.visibility != View.VISIBLE) {
            if (handleForwardedKeyEvent(event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleForwardedKeyEvent(event: KeyEvent): Boolean {
        if (!keyboardAllowedOnMac) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            -> return false
        }

        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }

        var mods = activeStickyModifiers
        if (event.isShiftPressed) mods = mods or 1
        if (event.isCtrlPressed) mods = mods or 2
        if (event.isAltPressed) mods = mods or 4
        if (event.isMetaPressed) mods = mods or 8

        val hasCommandMods = (mods and (2 or 4 or 8)) != 0
        val unicode = event.unicodeChar
        val isSpecialControlKey =
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DEL,
                KeyEvent.KEYCODE_FORWARD_DEL,
                KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_ESCAPE,
                -> true
                else -> false
            }

        if (!hasCommandMods && !isSpecialControlKey && unicode != 0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                streamClient?.sendKeyboardText(String(Character.toChars(unicode)))
            } else if (event.action == KeyEvent.ACTION_UP && activeStickyModifiers != 0) {
                clearStickyModifiers()
            }
            return true
        }

        streamClient?.sendKeyEvent(
            keyCode = event.keyCode,
            isDown = event.action == KeyEvent.ACTION_DOWN,
            modifiers = mods,
        )
        if (event.action == KeyEvent.ACTION_UP &&
            activeStickyModifiers != 0 &&
            !KeyEvent.isModifierKey(event.keyCode)
        ) {
            clearStickyModifiers()
        }
        return true
    }
}
