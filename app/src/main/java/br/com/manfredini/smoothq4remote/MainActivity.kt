package br.com.manfredini.smoothq4remote

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.EditText
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SmoothQ4BleClient.Listener {
    private var gimbalService: GimbalConnectionService? = null
    private var serviceBound = false
    private var scanWhenServiceReady = false
    private lateinit var statusView: TextView
    private lateinit var sensitivityValue: TextView
    private lateinit var panDirectionButton: Button
    private lateinit var xLockButton: Button
    private lateinit var yLockButton: Button
    private lateinit var poseTracker: PhonePoseTracker
    private val presetLabelFields = mutableListOf<EditText>()
    private val presetGoButtons = mutableListOf<Button>()
    private var targetPose: PhonePoseTracker.Pose? = null
    private var activePresetIndex = -1
    private var presetStartTime = 0L
    private var settledSamples = 0
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private val devices = mutableListOf<android.bluetooth.BluetoothDevice>()
    private val deviceLabels = mutableListOf<String>()
    private var deviceDialog: AlertDialog? = null
    private var joyX = 0f
    private var joyY = 0f
    private var sensitivity = DEFAULT_SENSITIVITY
    private var panInverted = false
    private var xAxisLocked = false
    private var yAxisLocked = false
    private var motionLoopActive = false
    private var autoConnectAttempted = false
    private val handler = Handler(Looper.getMainLooper())
    private val presetMotionRunnable = object : Runnable {
        override fun run() {
            val target = targetPose ?: return
            val current = poseTracker.currentPose
            if (current == null) {
                cancelPresetMotion("Sensor de orientação sem leitura; movimento interrompido.")
                return
            }
            if (System.currentTimeMillis() - presetStartTime > MAX_PRESET_DURATION_MS) {
                cancelPresetMotion("Tempo limite do preset; confira o enquadramento.")
                return
            }
            val yawError = shortestAngle(target.yawDegrees - current.yawDegrees)
            val pitchError = target.pitchDegrees - current.pitchDegrees
            if (abs(yawError) <= PRESET_TOLERANCE_DEGREES && abs(pitchError) <= PRESET_TOLERANCE_DEGREES) {
                settledSamples += 1
                gimbalService?.sendAxes(0f, 0f)
                if (settledSamples >= PRESET_SETTLED_SAMPLES) {
                    targetPose = null
                    activePresetIndex = -1
                    refreshPresetButtons()
                    showStatus("Enquadramento do preset alcançado (estimativa do celular).")
                    return
                }
            } else {
                settledSamples = 0
                val rawPan = proportionalCommand(yawError, PRESET_PAN_GAIN)
                val pan = if (panInverted) -rawPan else rawPan
                // The joystick's positive vertical command points the camera down;
                // elevation error has the opposite sign.
                val tilt = if (yAxisLocked) 0f else proportionalCommand(-pitchError, PRESET_TILT_GAIN)
                val allowedPan = if (xAxisLocked) 0f else pan
                gimbalService?.sendAxes(allowedPan, tilt)
            }
            handler.postDelayed(this, PRESET_CONTROL_PERIOD_MS)
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? GimbalConnectionService.LocalBinder ?: return
            gimbalService = binder.getService().also { it.setObserver(this@MainActivity) }
            if (scanWhenServiceReady) beginBluetoothScan() else connectToLastGimbal()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gimbalService?.setObserver(null)
            gimbalService = null
        }
    }
    private val motionRunnable = object : Runnable {
        override fun run() {
            if (!motionLoopActive) return
            if (gimbalService?.isReady() == true) {
                val panValue = axisInput(if (xAxisLocked) 0f else joyX)
                val pan = if (panInverted) -panValue else panValue
                // JoystickView reports screen coordinates: positive Y is downward.
                val tilt = axisInput(if (yAxisLocked) 0f else joyY)
                // Zhiyun joystick frames carry both axes; keep the inactive axis centered.
                gimbalService?.sendAxes(pan, tilt)
            }
            handler.postDelayed(this, JOYSTICK_PERIOD_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF101820.toInt()
        window.navigationBarColor = 0xFF101820.toInt()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        poseTracker = PhonePoseTracker(this)
        buildInterface()
        requestBlePermissions()
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(
            Intent(this, GimbalConnectionService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        gimbalService?.setObserver(null)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        gimbalService = null
        super.onStop()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101820.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "SMOOTH 4 • TESTE"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        val connectButton = button("Conectar")
        connectButton.setOnClickListener { openDevicePicker() }
        header.addView(connectButton, LinearLayout.LayoutParams(dp(112), dp(46)))
        val disconnectButton = button("Desconectar")
        disconnectButton.setOnClickListener {
            stopJoystick()
            startGimbalService(GimbalConnectionService.ACTION_DISCONNECT)
            showStatus("Desconectando do gimbal…")
        }
        header.addView(disconnectButton, LinearLayout.LayoutParams(dp(122), dp(46)).apply {
            leftMargin = dp(8)
        })
        root.addView(header)

        statusView = TextView(this).apply {
            text = "Conecte o gimbal para começar."
            textSize = 13f
            setTextColor(0xFFB8C7D1.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(statusView, LinearLayout.LayoutParams(0, dp(44), 1f))
        val infoButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_info)
            setBackgroundColor(0xFF244254.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Informações do aplicativo"
            setOnClickListener { showAboutDialog() }
        }
        statusRow.addView(infoButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            leftMargin = dp(8)
        })
        root.addView(statusRow, LinearLayout.LayoutParams(-1, dp(46)))

        root.addView(label("Arraste o controle para mover pan (esquerda/direita) e tilt (cima/baixo). Solte para parar.").apply {
            gravity = Gravity.CENTER
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }, LinearLayout.LayoutParams(-1, dp(48)))

        val sensitivityHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        sensitivityHeader.addView(label("Sensibilidade"), LinearLayout.LayoutParams(0, dp(30), 1f))
        sensitivityValue = label("${(sensitivity * 100).toInt()}%").apply {
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        sensitivityHeader.addView(sensitivityValue, LinearLayout.LayoutParams(dp(56), dp(30)))
        root.addView(sensitivityHeader)

        val sensitivityBar = SeekBar(this).apply {
            max = 100
            progress = (DEFAULT_SENSITIVITY * 100).toInt()
            contentDescription = "Sensibilidade do joystick"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    sensitivity = progress.coerceAtLeast(MIN_SENSITIVITY_PERCENT) / 100f
                    sensitivityValue.text = "${(sensitivity * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        root.addView(sensitivityBar, LinearLayout.LayoutParams(-1, dp(52)))

        val testControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panDirectionButton = button(panDirectionLabel())
        panDirectionButton.setOnClickListener {
            panInverted = !panInverted
            panDirectionButton.text = panDirectionLabel()
            panDirectionButton.setBackgroundColor(if (panInverted) 0xFF176B70.toInt() else 0xFF244254.toInt())
            showStatus(if (panInverted) "Pan invertido: esquerda e direita trocadas." else "Pan normal: sentido padrão.")
        }
        testControls.addView(panDirectionButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            rightMargin = dp(4)
        })
        xLockButton = button(xAxisLockLabel())
        xLockButton.contentDescription = "Travar ou destravar o eixo X, pan horizontal"
        xLockButton.setOnClickListener {
            xAxisLocked = !xAxisLocked
            if (xAxisLocked) joyX = 0f
            refreshAxisLockButtons()
            stopIfAllMovingAxesAreLockedOrCentered()
            showStatus(if (xAxisLocked) "Eixo X travado; Y continua livre." else "Eixo X liberado.")
        }
        testControls.addView(xLockButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(4)
        })
        yLockButton = button(yAxisLockLabel())
        yLockButton.contentDescription = "Travar ou destravar o eixo Y, tilt vertical"
        yLockButton.setOnClickListener {
            yAxisLocked = !yAxisLocked
            if (yAxisLocked) joyY = 0f
            refreshAxisLockButtons()
            stopIfAllMovingAxesAreLockedOrCentered()
            showStatus(if (yAxisLocked) "Eixo Y travado; X continua livre." else "Eixo Y liberado.")
        }
        testControls.addView(yLockButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(4)
        })
        root.addView(testControls, LinearLayout.LayoutParams(-1, dp(50)).apply {
            bottomMargin = dp(2)
        })

        val controlArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val joystick = JoystickView(this).apply {
            listener = JoystickView.Listener { x, y, released ->
                if (targetPose != null) cancelPresetMotion("Preset interrompido pelo joystick.")
                joyX = if (xAxisLocked) 0f else x
                joyY = if (yAxisLocked) 0f else y
                if (released || (abs(joyX) < DEAD_ZONE && abs(joyY) < DEAD_ZONE)) {
                    stopJoystick()
                } else if (!motionLoopActive) {
                    motionLoopActive = true
                    handler.post(motionRunnable)
                }
            }
        }
        controlArea.addView(joystick, LinearLayout.LayoutParams(0, -1, 1f).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
            rightMargin = dp(6)
        })
        val presetPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        presetPanel.addView(label("POSIÇÕES").apply {
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-1, dp(24)))
        repeat(PRESET_COUNT) { index ->
            val savedLabel = getSharedPreferences(PRESET_PREFS, MODE_PRIVATE)
                .getString(presetLabelKey(index), "Cena ${index + 1}") ?: "Cena ${index + 1}"
            val nameField = EditText(this).apply {
                setSingleLine(true)
                textSize = 10f
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF8FA5B3.toInt())
                hint = "Nome ${index + 1}"
                setText(savedLabel)
                setPadding(dp(4), 0, dp(4), 0)
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF527080.toInt())
            }
            presetLabelFields += nameField
            presetPanel.addView(nameField, LinearLayout.LayoutParams(-1, dp(34)))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val saveButton = button("Salvar").apply {
                textSize = 9f
                minHeight = dp(34)
                setPadding(dp(2), 0, dp(2), 0)
                setOnClickListener { savePreset(index) }
            }
            val goButton = button("Ir").apply {
                textSize = 10f
                minHeight = dp(34)
                setPadding(dp(2), 0, dp(2), 0)
                setOnClickListener { moveToPreset(index) }
            }
            presetGoButtons += goButton
            actions.addView(saveButton, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(2) })
            actions.addView(goButton, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(2) })
            presetPanel.addView(actions, LinearLayout.LayoutParams(-1, dp(38)))
            if (index < PRESET_COUNT - 1) presetPanel.addView(android.view.View(this).apply {
                setBackgroundColor(0xFF314653.toInt())
            }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(5)
                bottomMargin = dp(5)
            })
        }
        controlArea.addView(presetPanel, LinearLayout.LayoutParams(dp(112), -1))
        root.addView(controlArea, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), dp(10) + bars.top, dp(16), dp(12) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun connectToLastGimbal() {
        if (autoConnectAttempted || !hasBlePermissions()) return
        val address = getSharedPreferences(GimbalConnectionService.PREFS_FILE, MODE_PRIVATE)
            .getString(GimbalConnectionService.PREF_KEY_LAST_GIMBAL_ADDRESS, null)
            ?.takeIf { it.isNotBlank() } ?: return
        autoConnectAttempted = true
        showStatus("Conectando ao último Smooth 4 salvo…")
        startGimbalService(GimbalConnectionService.ACTION_CONNECT, address)
    }

    private fun openDevicePicker() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        devices.clear()
        deviceLabels.clear()
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val deviceList = ListView(this).apply {
            adapter = deviceAdapter
            setOnItemClickListener { _, _, which, _ ->
                val selectedLabel = deviceAdapter.getItem(which) ?: return@setOnItemClickListener
                val deviceIndex = deviceLabels.indexOf(selectedLabel)
                if (deviceIndex in devices.indices) {
                    val device = devices[deviceIndex]
                    getSharedPreferences(GimbalConnectionService.PREFS_FILE, MODE_PRIVATE)
                        .edit()
                        .putString(GimbalConnectionService.PREF_KEY_LAST_GIMBAL_ADDRESS, device.address)
                        .apply()
                    deviceDialog?.dismiss()
                    startGimbalService(GimbalConnectionService.ACTION_CONNECT, device.address)
                }
            }
        }
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(deviceList, LinearLayout.LayoutParams(-1, dp(412)))
        }
        deviceDialog = AlertDialog.Builder(this)
            .setTitle("Selecione o Smooth 4")
            .setView(dialogContent)
            .setNegativeButton("Fechar") { dialog, _ ->
                dialog.dismiss()
                gimbalService?.stopScan()
            }
            .create()
        deviceDialog?.show()
        scanWhenServiceReady = gimbalService == null
        if (gimbalService != null) beginBluetoothScan()
    }

    private fun beginBluetoothScan() {
        scanWhenServiceReady = false
        startGimbalService(GimbalConnectionService.ACTION_SCAN)
    }

    private fun startGimbalService(action: String, deviceAddress: String? = null) {
        val intent = Intent(this, GimbalConnectionService::class.java).setAction(action)
        if (deviceAddress != null) {
            intent.putExtra(GimbalConnectionService.EXTRA_DEVICE_ADDRESS, deviceAddress)
        }
        if (action == GimbalConnectionService.ACTION_DISCONNECT) {
            startService(intent)
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun refreshDeviceAdapter() {
        if (!::deviceAdapter.isInitialized) return
        deviceAdapter.clear()
        deviceLabels.forEach(deviceAdapter::add)
        deviceAdapter.notifyDataSetChanged()
    }

    private fun savePreset(index: Int) {
        val pose = poseTracker.currentPose
        if (!poseTracker.isAvailable || pose == null) {
            showStatus("Aguardando leitura do sensor de orientação; tente novamente.")
            return
        }
        val name = presetLabelFields[index].text.toString().trim().ifBlank { "Posição ${index + 1}" }
        getSharedPreferences(PRESET_PREFS, MODE_PRIVATE).edit()
            .putString(presetLabelKey(index), name)
            .putFloat(presetYawKey(index), pose.yawDegrees)
            .putFloat(presetPitchKey(index), pose.pitchDegrees)
            .putBoolean(presetSavedKey(index), true)
            .apply()
        presetLabelFields[index].setText(name)
        showStatus("Preset '$name' salvo. Ângulos estimados pelo celular.")
    }

    private fun moveToPreset(index: Int) {
        if (activePresetIndex == index && targetPose != null) {
            cancelPresetMotion("Movimento do preset cancelado.")
            return
        }
        if (gimbalService?.isReady() != true) {
            showStatus("Conecte o Smooth 4 antes de usar os presets.")
            return
        }
        val preferences = getSharedPreferences(PRESET_PREFS, MODE_PRIVATE)
        if (!preferences.getBoolean(presetSavedKey(index), false)) {
            showStatus("Salve uma posição nesse botão primeiro.")
            return
        }
        val current = poseTracker.currentPose
        if (!poseTracker.isAvailable || current == null) {
            showStatus("Sensor de orientação sem leitura; preset não iniciado.")
            return
        }
        val target = PhonePoseTracker.Pose(
            preferences.getFloat(presetYawKey(index), current.yawDegrees),
            preferences.getFloat(presetPitchKey(index), current.pitchDegrees)
        )
        handler.removeCallbacks(presetMotionRunnable)
        targetPose = target
        activePresetIndex = index
        presetStartTime = System.currentTimeMillis()
        settledSamples = 0
        motionLoopActive = false
        handler.removeCallbacks(motionRunnable)
        refreshPresetButtons()
        showStatus("Indo para '${presetLabelFields[index].text}' (estimativa IMU; toque Parar para cancelar).")
        handler.post(presetMotionRunnable)
    }

    private fun cancelPresetMotion(message: String, updateStatus: Boolean = true) {
        if (targetPose == null) return
        targetPose = null
        activePresetIndex = -1
        handler.removeCallbacks(presetMotionRunnable)
        gimbalService?.stopMotion()
        refreshPresetButtons()
        if (updateStatus) showStatus(message)
    }

    private fun refreshPresetButtons() {
        presetGoButtons.forEachIndexed { index, button ->
            button.text = if (targetPose != null && activePresetIndex == index) "Parar" else "Ir"
        }
    }

    private fun shortestAngle(degrees: Float): Float = ((degrees + 540f) % 360f) - 180f

    private fun proportionalCommand(error: Float, gain: Float): Float {
        if (abs(error) <= PRESET_TOLERANCE_DEGREES) return 0f
        val magnitude = (abs(error) * gain).coerceIn(PRESET_MIN_SPEED, PRESET_MAX_SPEED)
        return if (error >= 0f) magnitude else -magnitude
    }

    private fun presetLabelKey(index: Int) = "preset_${index}_label"
    private fun presetYawKey(index: Int) = "preset_${index}_yaw"
    private fun presetPitchKey(index: Int) = "preset_${index}_pitch"
    private fun presetSavedKey(index: Int) = "preset_${index}_saved"

    private fun requestBlePermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filterNot(::hasPermission)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), BLE_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != BLE_PERMISSION_REQUEST) return
        if (hasBlePermissions()) {
            showStatus("Permissão pronta.")
            connectToLastGimbal()
        } else {
            showStatus("Permita o Bluetooth para localizar o Smooth 4.")
        }
    }

    private fun hasBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun axisInput(value: Float): Float =
        if (abs(value) < DEAD_ZONE) 0f else value.coerceIn(-1f, 1f) * sensitivity

    private fun stopJoystick() {
        joyX = 0f
        joyY = 0f
        if (motionLoopActive) {
            motionLoopActive = false
            handler.removeCallbacks(motionRunnable)
            if (gimbalService?.isReady() == true) gimbalService?.stopMotion()
        }
    }

    override fun onStatus(message: String) = showStatus(message)

    override fun onDevicesFound(foundDevices: List<android.bluetooth.BluetoothDevice>) {
        if (!::deviceAdapter.isInitialized) return
        val lastAddress = getSharedPreferences(GimbalConnectionService.PREFS_FILE, MODE_PRIVATE)
            .getString(GimbalConnectionService.PREF_KEY_LAST_GIMBAL_ADDRESS, null)
        devices.clear()
        devices.addAll(
            foundDevices.distinctBy { it.address }
                .sortedWith(
                    compareBy<android.bluetooth.BluetoothDevice> {
                        when {
                            it.address.equals(lastAddress, ignoreCase = true) -> 0
                            isLikelyGimbal(safeDeviceName(it)) -> 1
                            else -> 2
                        }
                    }.thenBy { safeDeviceName(it).lowercase() }
                )
        )
        deviceLabels.clear()
        devices.forEach { device ->
            val address = device.address
            val name = safeDeviceName(device)
            val prefix = when {
                address.equals(lastAddress, ignoreCase = true) -> "★ ÚLTIMO GIMBAL"
                isLikelyGimbal(name) -> "◆ POSSÍVEL SMOOTH 4"
                else -> ""
            }
            deviceLabels += listOf(prefix, name).filter { it.isNotBlank() }.joinToString(" — ") + "  •  $address"
        }
        refreshDeviceAdapter()
    }

    override fun onReady() {
        deviceDialog?.dismiss()
        showStatus("Conectado. Movimento nos eixos em teste.")
    }

    override fun onPacketReceived(bytes: ByteArray) {
        if (bytes.isNotEmpty()) showStatus("Resposta do gimbal recebida por Bluetooth.")
    }

    private fun safeDeviceName(device: android.bluetooth.BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: "Dispositivo sem nome"
    } catch (_: SecurityException) {
        "Dispositivo Bluetooth"
    }

    private fun isLikelyGimbal(name: String): Boolean {
        val normalized = name.lowercase().replace(" ", "")
        return normalized.contains("smooth") || normalized.contains("zhiyun") || normalized.startsWith("zy")
    }

    private fun showStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun showAboutDialog() {
        val aboutText = TextView(this).apply {
            text = "Smooth 4 Remote\n\nDesenvolvido por Anderson Manfredini\nTelefone: 12 98801-5750"
            textSize = 16f
            setTextColor(0xFFB8C7D1.toInt())
            autoLinkMask = Linkify.PHONE_NUMBERS
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Informações")
            .setView(aboutText)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun refreshAxisLockButtons() {
        xLockButton.text = xAxisLockLabel()
        xLockButton.setBackgroundColor(if (xAxisLocked) 0xFF176B70.toInt() else 0xFF244254.toInt())
        yLockButton.text = yAxisLockLabel()
        yLockButton.setBackgroundColor(if (yAxisLocked) 0xFF176B70.toInt() else 0xFF244254.toInt())
    }

    private fun stopIfAllMovingAxesAreLockedOrCentered() {
        if ((xAxisLocked || abs(joyX) < DEAD_ZONE) && (yAxisLocked || abs(joyY) < DEAD_ZONE)) {
            stopJoystick()
        }
    }

    private fun panDirectionLabel() = if (panInverted) "Pan invertido" else "Pan normal"

    private fun xAxisLockLabel() = if (xAxisLocked) "X travado" else "Travar X"

    private fun yAxisLockLabel() = if (yAxisLocked) "Y travado" else "Travar Y"

    private fun button(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        minHeight = dp(44)
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF244254.toInt())
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFFB8C7D1.toInt())
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        poseTracker.start()
    }

    override fun onPause() {
        cancelPresetMotion("Preset interrompido ao sair da tela.", updateStatus = false)
        stopJoystick()
        poseTracker.stop()
        super.onPause()
    }

    override fun onDestroy() {
        stopJoystick()
        super.onDestroy()
    }

    companion object {
        private const val DEAD_ZONE = 0.08f
        private const val MIN_SENSITIVITY_PERCENT = 15
        private const val DEFAULT_SENSITIVITY = 0.65f
        private const val JOYSTICK_PERIOD_MS = 100L
        private const val BLE_PERMISSION_REQUEST = 2401
        private const val PRESET_PREFS = "smooth4_position_presets_test"
        private const val PRESET_COUNT = 3
        private const val PRESET_CONTROL_PERIOD_MS = 100L
        private const val MAX_PRESET_DURATION_MS = 12_000L
        private const val PRESET_SETTLED_SAMPLES = 3
        private const val PRESET_TOLERANCE_DEGREES = 3f
        private const val PRESET_PAN_GAIN = 0.012f
        private const val PRESET_TILT_GAIN = 0.012f
        private const val PRESET_MIN_SPEED = 0.07f
        private const val PRESET_MAX_SPEED = 0.30f
    }
}

