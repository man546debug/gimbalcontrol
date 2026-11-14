package br.com.manfredini.smoothq4remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class GimbalConnectionService : Service(), SmoothQ4BleClient.Listener {
    inner class LocalBinder : Binder() {
        fun getService(): GimbalConnectionService = this@GimbalConnectionService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private lateinit var ble: SmoothQ4BleClient
    private var observer: SmoothQ4BleClient.Listener? = null
    private var lastStatus = "Conecte o gimbal para começar."
    private var lastDevices = emptyList<BluetoothDevice>()
    private var targetAddress: String? = null
    private var shouldReconnect = false
    private var reconnectScheduled = false
    private var connectAttemptActive = false
    private var reconnectCount = 0

    private val scanFinishRunnable = Runnable {
        ble.stopScan()
        if (!shouldReconnect) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private val connectTimeoutRunnable = Runnable {
        if (shouldReconnect && connectAttemptActive && !ble.isReady()) {
            connectAttemptActive = false
            ble.disconnect(sendStop = false)
            scheduleReconnect()
        }
    }

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        val address = targetAddress ?: return@Runnable
        if (shouldReconnect) connectToAddress(address)
    }

    override fun onCreate() {
        super.onCreate()
        ble = SmoothQ4BleClient(applicationContext, this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCAN -> {
                startAsForeground("Procurando o Smooth 4 por Bluetooth…")
                handler.removeCallbacks(scanFinishRunnable)
                ble.startScan()
                handler.postDelayed(scanFinishRunnable, SCAN_WINDOW_MS)
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                targetAddress = address
                shouldReconnect = true
                reconnectCount = 0
                getSharedPreferences(PREFS_FILE, MODE_PRIVATE).edit()
                    .putString(PREF_KEY_LAST_GIMBAL_ADDRESS, address)
                    .putBoolean(PREF_KEY_ACTIVE_CONNECTION, true)
                    .apply()
                startAsForeground("Conectando ao Smooth 4…")
                connectToAddress(address)
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                shouldReconnect = false
                targetAddress = null
                reconnectScheduled = false
                connectAttemptActive = false
                handler.removeCallbacks(reconnectRunnable)
                handler.removeCallbacks(connectTimeoutRunnable)
                handler.removeCallbacks(scanFinishRunnable)
                getSharedPreferences(PREFS_FILE, MODE_PRIVATE).edit()
                    .putBoolean(PREF_KEY_ACTIVE_CONNECTION, false)
                    .apply()
                ble.stopScan()
                lastStatus = "Desconectando do gimbal…"
                observer?.onStatus(lastStatus)
                ble.disconnect(sendStop = true)
                handler.postDelayed({
                    lastStatus = "Desconectado."
                    observer?.onStatus(lastStatus)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }, DISCONNECT_GRACE_MS)
                return START_NOT_STICKY
            }
            null -> {
                val preferences = getSharedPreferences(PREFS_FILE, MODE_PRIVATE)
                val restore = preferences.getBoolean(PREF_KEY_ACTIVE_CONNECTION, false)
                val address = preferences.getString(PREF_KEY_LAST_GIMBAL_ADDRESS, null)
                if (restore && !address.isNullOrBlank()) {
                    targetAddress = address
                    shouldReconnect = true
                    startAsForeground("Restabelecendo a conexão com o Smooth 4…")
                    handler.postDelayed({ connectToAddress(address) }, INITIAL_RECONNECT_DELAY_MS)
                    return START_STICKY
                }
                stopSelf(startId)
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    fun setObserver(listener: SmoothQ4BleClient.Listener?) {
        observer = listener
        if (listener != null) {
            listener.onStatus(if (ble.isReady()) "Smooth 4 conectado." else lastStatus)
            if (lastDevices.isNotEmpty()) listener.onDevicesFound(lastDevices)
            if (ble.isReady()) listener.onReady()
        }
    }

    fun isReady(): Boolean = ble.isReady()

    fun sendAxes(pan: Float, tilt: Float) = ble.sendAxes(pan, tilt)

    fun stopMotion() = ble.stopMotion()

    fun stopScan() {
        handler.removeCallbacks(scanFinishRunnable)
        ble.stopScan()
        if (!shouldReconnect) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToAddress(address: String) {
        if (!shouldReconnect) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            postStatus("Ative o Bluetooth; o aplicativo tentará novamente.")
            scheduleReconnect()
            return
        }
        try {
            connectAttemptActive = true
            ble.connect(adapter.getRemoteDevice(address))
            updateNotification("Mantendo a conexão Bluetooth com o Smooth 4")
            handler.removeCallbacks(connectTimeoutRunnable)
            handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
        } catch (_: IllegalArgumentException) {
            connectAttemptActive = false
            postStatus("O endereço salvo do gimbal não é válido. Faça uma nova busca.")
        } catch (_: SecurityException) {
            connectAttemptActive = false
            postStatus("Permissão Bluetooth necessária para reconectar ao gimbal.")
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || targetAddress == null || reconnectScheduled) return
        reconnectScheduled = true
        val delay = when (reconnectCount) {
            0 -> INITIAL_RECONNECT_DELAY_MS
            1 -> 2_000L
            2 -> 4_000L
            3 -> 8_000L
            4 -> 16_000L
            else -> MAX_RECONNECT_DELAY_MS
        }
        reconnectCount += 1
        updateNotification("Conexão interrompida; tentando novamente em ${delay / 1000}s")
        handler.removeCallbacks(connectTimeoutRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    override fun onStatus(message: String) {
        lastStatus = message
        observer?.onStatus(message)
        when {
            message.startsWith("Conectado. Lendo serviços") -> {
                updateNotification("Smooth 4 conectado; finalizando o canal de controle")
            }
            message == "Gimbal desconectado." ||
                message.startsWith("Falha na conexão BLE") ||
                message.startsWith("Não foi possível descobrir os serviços BLE") -> {
                connectAttemptActive = false
                if (shouldReconnect) scheduleReconnect()
            }
        }
    }

    override fun onDevicesFound(devices: List<BluetoothDevice>) {
        lastDevices = devices.toList()
        observer?.onDevicesFound(lastDevices)
    }

    override fun onReady() {
        handler.removeCallbacks(connectTimeoutRunnable)
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        connectAttemptActive = false
        reconnectCount = 0
        updateNotification("Conectado ao Smooth 4; conexão mantida com a tela apagada")
        lastStatus = "Conectado ao Smooth 4."
        observer?.onReady()
    }

    override fun onPacketReceived(bytes: ByteArray) {
        observer?.onPacketReceived(bytes)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::ble.isInitialized) ble.disconnect(sendStop = false)
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startAsForeground(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnect = PendingIntent.getForegroundService(
            this,
            1,
            Intent(this, GimbalConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_status)
            .setContentTitle("Smooth 4 Remote")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(shouldReconnect)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_bluetooth_status, "Desconectar", disconnect)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Conexão do Smooth 4",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a conexão Bluetooth do gimbal ativa com a tela desligada."
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun postStatus(message: String) {
        lastStatus = message
        observer?.onStatus(message)
    }

    companion object {
        const val ACTION_SCAN = "br.com.manfredini.smoothq4remote.action.SCAN"
        const val ACTION_CONNECT = "br.com.manfredini.smoothq4remote.action.CONNECT"
        const val ACTION_DISCONNECT = "br.com.manfredini.smoothq4remote.action.DISCONNECT"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val PREFS_FILE = "smooth4_connection"
        const val PREF_KEY_LAST_GIMBAL_ADDRESS = "last_gimbal_address"
        private const val PREF_KEY_ACTIVE_CONNECTION = "active_connection"
        private const val CHANNEL_ID = "smooth4_connection_channel"
        private const val NOTIFICATION_ID = 4104
        private const val SCAN_WINDOW_MS = 13_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val DISCONNECT_GRACE_MS = 350L
    }
}
