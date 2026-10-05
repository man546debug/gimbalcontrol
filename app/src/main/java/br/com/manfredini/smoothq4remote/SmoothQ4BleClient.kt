package br.com.manfredini.smoothq4remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SmoothQ4BleClient(context: Context, private val listener: Listener) {
    private data class PendingWrite(val packet: ByteArray, val retries: Int = 0)

    interface Listener {
        fun onStatus(message: String)
        fun onDevicesFound(devices: List<BluetoothDevice>)
        fun onReady()
        fun onPacketReceived(bytes: ByteArray)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val sequence = AtomicInteger(0)
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private val writeQueue = java.util.ArrayDeque<PendingWrite>()
    private var writeDrainScheduled = false
    private val writeDrainRunnable = Runnable {
        writeDrainScheduled = false
        writeNextPacket()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val key = device.address ?: return
            found[key] = device
            val devices = found.values.toList()
            mainHandler.post { listener.onDevicesFound(devices) }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post { listener.onStatus("Busca Bluetooth falhou (código $errorCode).") }
        }
    }

    private val stopScanRunnable = Runnable { stopScan() }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            status("Ative o Bluetooth do celular e tente novamente.")
            return
        }
        stopScan()
        found.clear()
        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            status("O celular não disponibilizou o scanner BLE.")
            return
        }
        scanner?.startScan(scanCallback)
        status("Procurando Smooth 4 por até 12 segundos…")
        mainHandler.postDelayed(stopScanRunnable, 12_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        mainHandler.removeCallbacks(stopScanRunnable)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Permission can be revoked while the screen is open.
        }
        scanner = null
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        disconnect(sendStop = false)
        status("Conectando a ${safeName(device)}…")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendAxes(pan: Float, tilt: Float) {
        if (writeCharacteristic == null || gatt == null) return
        val packets = SmoothQ4Protocol.encodeAxes(pan, tilt, sequence.getAndAdd(3))
        synchronized(writeQueue) {
            packets.forEach { writeQueue.addLast(PendingWrite(it)) }
        }
        scheduleWriteDrain()
    }

    fun stopMotion() {
        synchronized(writeQueue) { writeQueue.clear() }
        sendAxes(0f, 0f)
    }

    fun isReady(): Boolean = writeCharacteristic != null && gatt != null

    @SuppressLint("MissingPermission")
    fun disconnect(sendStop: Boolean = true) {
        if (sendStop && isReady()) {
            stopMotion()
            val closingGatt = gatt
            mainHandler.postDelayed({ closeGatt(closingGatt) }, WRITE_INTERVAL_MS * 3)
            return
        }
        stopScan()
        mainHandler.removeCallbacks(writeDrainRunnable)
        writeDrainScheduled = false
        synchronized(writeQueue) { writeQueue.clear() }
        closeGatt(gatt)
    }

    private fun scheduleWriteDrain(delayMs: Long = WRITE_INTERVAL_MS) {
        if (writeDrainScheduled) return
        writeDrainScheduled = true
        mainHandler.postDelayed(writeDrainRunnable, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun writeNextPacket() {
        val command = synchronized(writeQueue) {
            if (writeQueue.isEmpty()) null else writeQueue.removeFirst()
        } ?: return
        val connection = gatt
        val characteristic = writeCharacteristic
        if (connection == null || characteristic == null) {
            synchronized(writeQueue) { writeQueue.clear() }
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val result = connection.writeCharacteristic(
                    characteristic,
                    command.packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    retryOrReport(command, "BLE recusou um comando ($result).")
                    return
                }
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = command.packet
                if (!connection.writeCharacteristic(characteristic)) {
                    retryOrReport(command, "BLE recusou um comando.")
                    return
                }
            }
        } catch (_: SecurityException) {
            retryOrReport(command, "Permissão Bluetooth removida; reconecte o gimbal.")
            return
        }
        if (synchronized(writeQueue) { writeQueue.isNotEmpty() }) scheduleWriteDrain()
    }

    private fun retryOrReport(command: PendingWrite, message: String) {
        if (command.retries < MAX_WRITE_RETRIES && isReady()) {
            synchronized(writeQueue) { writeQueue.addFirst(command.copy(retries = command.retries + 1)) }
            scheduleWriteDrain(WRITE_RETRY_INTERVAL_MS)
        } else {
            status(message)
            if (synchronized(writeQueue) { writeQueue.isNotEmpty() }) scheduleWriteDrain()
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(connection: BluetoothGatt?) {
        if (connection == null) return
        try {
            connection.disconnect()
            connection.close()
        } catch (_: SecurityException) {
        }
        if (gatt === connection) {
            gatt = null
            writeCharacteristic = null
            notifyCharacteristic = null
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("Falha na conexão BLE ($statusCode).")
                disconnect(sendStop = false)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    status("Conectado. Lendo serviços do gimbal…")
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    writeCharacteristic = null
                    notifyCharacteristic = null
                    status("Gimbal desconectado.")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("Não foi possível descobrir os serviços BLE ($statusCode).")
                return
            }
            val characteristics = g.services.flatMap { it.characteristics }
            writeCharacteristic = characteristics.firstOrNull {
                it.uuid == SmoothQ4Protocol.writeCharacteristic &&
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE)) != 0
            }
            notifyCharacteristic = characteristics.firstOrNull {
                it.uuid == SmoothQ4Protocol.notifyCharacteristic &&
                    (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            }

            if (writeCharacteristic == null) {
                val summary = characteristics.joinToString { "${it.uuid} props=${it.properties}" }
                status("Característica de escrita conhecida não encontrada. BLE viu: $summary")
                return
            }

            notifyCharacteristic?.let { characteristic ->
                g.setCharacteristicNotification(characteristic, true)
                characteristic.getDescriptor(CLIENT_CONFIGURATION)?.let { descriptor ->
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(descriptor)
                    }
                }
            }
            status("Canal BLE pronto. Protocolo de movimento ainda em teste no Smooth 4.")
            mainHandler.post { listener.onReady() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value?.clone() ?: return
            mainHandler.post { listener.onPacketReceived(bytes) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val bytes = value.clone()
            mainHandler.post { listener.onPacketReceived(bytes) }
        }
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name ?: "Smooth 4 (${device.address})"
    } catch (_: SecurityException) {
        "Gimbal BLE"
    }

    private fun status(message: String) = mainHandler.post { listener.onStatus(message) }

    companion object {
        private const val WRITE_INTERVAL_MS = 30L
        private const val WRITE_RETRY_INTERVAL_MS = 60L
        private const val MAX_WRITE_RETRIES = 4
        private val CLIENT_CONFIGURATION = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
