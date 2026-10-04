package br.com.manfredini.smoothq4remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SmoothQ4BleClient.Listener {
    private lateinit var ble: SmoothQ4BleClient
    private lateinit var statusView: TextView
    private lateinit var sensitivityValue: TextView
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private val devices = mutableListOf<android.bluetooth.BluetoothDevice>()
    private val deviceLabels = mutableListOf<String>()
    private var deviceDialog: AlertDialog? = null
    private var joyX = 0f
    private var joyY = 0f
    private var sensitivity = DEFAULT_SENSITIVITY
    private var motionLoopActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val motionRunnable = object : Runnable {
        override fun run() {
            if (!motionLoopActive) return
            if (ble.isReady()) {
                val pan = axisInput(joyX)
                // JoystickView reports screen coordinates: positive Y is downward.
                val tilt = axisInput(joyY)
                // Zhiyun joystick frames carry both axes; keep the inactive axis centered.
                ble.sendAxes(pan, tilt)
            }
            handler.postDelayed(this, JOYSTICK_PERIOD_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF101820.toInt()
        window.navigationBarColor = 0xFF101820.toInt()
        ble = SmoothQ4BleClient(this, this)
        buildInterface()
        requestBlePermissions()
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
            ble.disconnect(sendStop = true)
            showStatus("Desconectado.")
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
        root.addView(statusView, LinearLayout.LayoutParams(-1, dp(38)))

        root.addView(label("Arraste o controle para mover pan (esquerda/direita) e tilt (cima/baixo). Solte para parar.").apply {
            gravity = Gravity.CENTER
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }, LinearLayout.LayoutParams(-1, dp(48)))

        val joystick = JoystickView(this).apply {
            listener = JoystickView.Listener { x, y, released ->
                joyX = x
                joyY = y
                if (released || (abs(x) < DEAD_ZONE && abs(y) < DEAD_ZONE)) {
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
        root.addView(sensitivityBar, LinearLayout.LayoutParams(-1, dp(54)))

        setContentView(root)
    }

    private fun openDevicePicker() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        devices.clear()
        deviceLabels.clear()
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceLabels)
        deviceDialog = AlertDialog.Builder(this)
            .setTitle("Dispositivos Bluetooth próximos")
            .setAdapter(deviceAdapter) { _, which ->
                if (which in devices.indices) ble.connect(devices[which])
            }
            .setNegativeButton("Fechar") { dialog, _ -> dialog.dismiss(); ble.stopScan() }
            .create()
        deviceDialog?.show()
        ble.startScan()
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
            if (ble.isReady()) ble.stopMotion()
        }
    }

    override fun onStatus(message: String) = showStatus(message)

    override fun onDevicesFound(foundDevices: List<android.bluetooth.BluetoothDevice>) {
        if (!::deviceAdapter.isInitialized) return
        val currentAddresses = devices.map { it.address }.toSet()
        foundDevices.filter { it.address !in currentAddresses }.forEach { device ->
            devices += device
            val name = try { device.name ?: "Dispositivo BLE" } catch (_: SecurityException) { "Dispositivo BLE" }
            deviceLabels += "$name  •  ${device.address}"
        }
        deviceAdapter.notifyDataSetChanged()
    }

    override fun onReady() {
        deviceDialog?.dismiss()
        showStatus("Conectado. Movimento nos eixos em teste.")
    }

    override fun onPacketReceived(bytes: ByteArray) {
        if (bytes.isNotEmpty()) showStatus("Resposta do gimbal recebida por Bluetooth.")
    }

    private fun showStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

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
        if (::ble.isInitialized) ble.disconnect(sendStop = true)
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
