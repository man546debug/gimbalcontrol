package br.com.manfredini.smoothq4remote

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SmoothQ4BleClient.Listener {
    private lateinit var ble: SmoothQ4BleClient
    private lateinit var statusView: TextView
    private lateinit var cameraController: LifecycleCameraController
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var zoomBar: SeekBar
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private val devices = mutableListOf<android.bluetooth.BluetoothDevice>()
    private val deviceLabels = mutableListOf<String>()
    private var deviceDialog: AlertDialog? = null
    private var cameraReady = false
    private var joyX = 0f
    private var joyY = 0f
    private var sensitivity = 0.45f
    private var motionLoopActive = false
    private var sequenceLastValue = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val motionRunnable = object : Runnable {
        override fun run() {
            if (!motionLoopActive) return
            if (ble.isReady()) {
                val pan = if (abs(joyX) < DEAD_ZONE) 0f else joyX * sensitivity
                val tilt = if (abs(joyY) < DEAD_ZONE) 0f else -joyY * sensitivity
                ble.sendAxis(SmoothQ4Protocol.PAN, pan, speedFromInput(pan))
                ble.sendAxis(SmoothQ4Protocol.TILT, tilt, speedFromInput(tilt))
            }
            handler.postDelayed(this, JOYSTICK_PERIOD_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true || hasPermission(Manifest.permission.CAMERA)
        if (cameraGranted) startCamera()
        if (hasBlePermissions()) ble.startScan()
        if (!cameraGranted) showStatus("A permissão da câmera é necessária para a pré-visualização e as fotos.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF101820.toInt()
        window.navigationBarColor = 0xFF101820.toInt()
        cameraExecutor = Executors.newSingleThreadExecutor()
        ble = SmoothQ4BleClient(this, this)
        buildInterface()
        requestMissingPermissions()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101820.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "SMOOTH Q4 REMOTE"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        val connectButton = button("Conectar")
        connectButton.setOnClickListener { openDevicePicker() }
        header.addView(connectButton)
        val disconnectButton = button("Parar")
        disconnectButton.setOnClickListener {
            joyX = 0f
            joyY = 0f
            motionLoopActive = false
            handler.removeCallbacks(motionRunnable)
            if (ble.isReady()) ble.stopMotion()
            ble.disconnect(sendStop = false)
            showStatus("Movimento parado; gimbal desconectado.")
        }
        header.addView(disconnectButton, LinearLayout.LayoutParams(dp(72), dp(42)).apply { leftMargin = dp(6) })
        root.addView(header)

        statusView = TextView(this).apply {
            text = "Pronto. Conecte o Smooth Q4."
            textSize = 12f
            setTextColor(0xFFB8C7D1.toInt())
            setPadding(dp(2), dp(3), dp(2), dp(7))
        }
        root.addView(statusView)

        val preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(0xFF000000.toInt())
        }
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f).apply {
            bottomMargin = dp(8)
        })

        cameraController = LifecycleCameraController(this).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
        preview.controller = cameraController

        val zoomRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        zoomRow.addView(label("Zoom"), LinearLayout.LayoutParams(dp(45), dp(40)))
        zoomBar = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || !cameraReady) return
                    val maxZoom = cameraController.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                    val ratio = 1f + (maxZoom - 1f) * progress / 100f
                    cameraController.setZoomRatio(ratio)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        zoomRow.addView(zoomBar, LinearLayout.LayoutParams(0, dp(40), 1f))
        val photoButton = button("FOTO")
        photoButton.setOnClickListener { takePhoto() }
        zoomRow.addView(photoButton, LinearLayout.LayoutParams(dp(86), dp(44)).apply { leftMargin = dp(8) })
        root.addView(zoomRow)

        root.addView(label("Arraste para mover pan e tilt. Solte para parar."), LinearLayout.LayoutParams(-1, dp(27)))
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
        root.addView(joystick, LinearLayout.LayoutParams(-1, dp(190)))

        val sensitivityRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sensitivityRow.addView(label("Sensibilidade"), LinearLayout.LayoutParams(dp(100), dp(38)))
        val sensitivityBar = SeekBar(this).apply {
            max = 100
            progress = 45
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    sensitivity = 0.15f + (progress / 100f) * 0.65f
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        sensitivityRow.addView(sensitivityBar, LinearLayout.LayoutParams(0, dp(38), 1f))
        root.addView(sensitivityRow)
        setContentView(root)
    }

    private fun openDevicePicker() {
        if (!hasBlePermissions()) {
            requestMissingPermissions()
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

    private fun requestMissingPermissions() {
        val required = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required += Manifest.permission.BLUETOOTH_SCAN
            required += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            required += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) required += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = required.distinct().filterNot(::hasPermission)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else startCamera()
    }

    private fun hasBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        try {
            cameraController.bindToLifecycle(this)
            cameraReady = true
            showStatus("Câmera pronta. Conecte o Smooth Q4 para habilitar o movimento.")
        } catch (error: Exception) {
            showStatus("Não foi possível abrir a câmera: ${error.message ?: "erro desconhecido"}")
        }
    }

    private fun takePhoto() {
        if (!cameraReady) {
            Toast.makeText(this, "A câmera ainda não está pronta.", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "SmoothQ4_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmoothQ4Remote")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            showStatus("Não consegui criar o arquivo da foto.")
            return
        }
        val options = ImageCapture.OutputFileOptions.Builder(contentResolver, uri, values).build()
        cameraController.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }
                runOnUiThread { showStatus("Foto salva em Pictures/SmoothQ4Remote.") }
            }

            override fun onError(exception: ImageCaptureException) {
                contentResolver.delete(uri, null, null)
                runOnUiThread { showStatus("Falha ao tirar foto: ${exception.message}") }
            }
        })
    }

    private fun stopJoystick() {
        joyX = 0f
        joyY = 0f
        if (motionLoopActive) {
            motionLoopActive = false
            handler.removeCallbacks(motionRunnable)
            if (ble.isReady()) ble.stopMotion()
        }
    }

    private fun speedFromInput(value: Float): Int = (8 + abs(value) * 42).toInt().coerceIn(8, 50)

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
        showStatus("Conectado. O protocolo de movimento precisa ser validado no Smooth Q4.")
    }

    override fun onPacketReceived(bytes: ByteArray) {
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        showStatus("BLE recebido: $hex")
    }

    private fun showStatus(message: String) {
        if (::statusView.isInitialized) statusView.text = message
    }

    private fun button(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        minHeight = dp(42)
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF244254.toInt())
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
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
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val DEAD_ZONE = 0.08f
        private const val JOYSTICK_PERIOD_MS = 100L
    }
}
