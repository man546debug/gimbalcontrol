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
import android.text.Editable
import android.text.method.LinkMovementMethod
import android.text.TextWatcher
import android.text.util.Linkify
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private var deviceFilter = ""
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
    private val handler = Handler(Looper.getMainLooper())
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? GimbalConnectionService.LocalBinder ?: return
            gimbalService = binder.getService().also { it.setObserver(this@MainActivity) }
            if (scanWhenServiceReady) beginBluetoothScan()
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
            text = "SMOOTH 4"
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

        val joystick = JoystickView(this).apply {
            listener = JoystickView.Listener { x, y, released ->
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
        root.addView(joystick, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), dp(10) + bars.top, dp(16), dp(12) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun openDevicePicker() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        devices.clear()
        deviceLabels.clear()
        deviceFilter = ""
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val searchField = EditText(this).apply {
            hint = "Filtrar por nome ou endereço"
            singleLine = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
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
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                deviceFilter = s?.toString().orEmpty()
                refreshDeviceAdapter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchField, LinearLayout.LayoutParams(-1, dp(52)))
            addView(deviceList, LinearLayout.LayoutParams(-1, dp(360)))
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
        val query = deviceFilter.trim()
        deviceAdapter.clear()
        deviceLabels.filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
            .forEach(deviceAdapter::add)
        deviceAdapter.notifyDataSetChanged()
    }

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
            showStatus("Permissão pronta. Toque em Conectar.")
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

    override fun onPause() {
        stopJoystick()
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
    }
}

