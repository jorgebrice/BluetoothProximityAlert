package com.example.bluetoothproximityalert

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class BluetoothMonitorService : Service() {

    companion object {
        private const val TAG = "BT_Monitor_Service"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bluetooth_monitor_channel"
        private const val ALERT_CHANNEL_ID = "bluetooth_alert_channel"
        
        // Umbrales de RSSI para alertas
        private const val RSSI_THRESHOLD_WARNING = -75  // Advertencia: señal débil
        private const val RSSI_THRESHOLD_CRITICAL = -85 // Crítico: muy alejado
        private const val RSSI_SCAN_INTERVAL = 2000L    // Escanear cada 2 segundos
        
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var currentDevice: BluetoothDevice? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var lastRssi = 0
    private var hasShownWarning = false
    private var hasShownCritical = false

    private val rssiRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring && bluetoothGatt != null) {
                readRssi()
                handler.postDelayed(this, RSSI_SCAN_INTERVAL)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceAddress = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                if (deviceAddress != null) {
                    startMonitoring(deviceAddress)
                }
            }
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                stopSelf()
            }
        }
        
        return START_STICKY
    }

    private fun startMonitoring(deviceAddress: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Permiso BLUETOOTH_CONNECT no otorgado")
                return
            }
        }

        try {
            currentDevice = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            
            val notification = createForegroundNotification(
                "Monitoreando dispositivo",
                "Conectado a ${currentDevice?.name ?: "Dispositivo"}"
            )
            
            startForeground(NOTIFICATION_ID, notification)
            
            // Conectar al dispositivo via GATT
            bluetoothGatt = currentDevice?.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar monitoreo: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Conectado al dispositivo GATT")
                    isMonitoring = true
                    handler.post(rssiRunnable)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Desconectado del dispositivo GATT")
                    isMonitoring = false
                    handler.removeCallbacks(rssiRunnable)
                    showDisconnectedNotification()
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                lastRssi = rssi
                Log.d(TAG, "RSSI actual: $rssi dBm")
                processRssi(rssi)
            }
        }
    }

    private fun readRssi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        bluetoothGatt?.readRemoteRssi()
    }

    private fun processRssi(rssi: Int) {
        when {
            rssi <= RSSI_THRESHOLD_CRITICAL -> {
                if (!hasShownCritical) {
                    showProximityAlert(
                        "¡Alerta Crítica!",
                        "Te estás alejando demasiado del dispositivo (${rssi} dBm). Regresa pronto o se desconectará.",
                        NotificationCompat.PRIORITY_MAX,
                        true
                    )
                    hasShownCritical = true
                    hasShownWarning = true
                }
            }
            rssi <= RSSI_THRESHOLD_WARNING -> {
                if (!hasShownWarning) {
                    showProximityAlert(
                        "Advertencia de Proximidad",
                        "Te estás alejando del dispositivo (${rssi} dBm). La señal se está debilitando.",
                        NotificationCompat.PRIORITY_HIGH,
                        false
                    )
                    hasShownWarning = true
                }
            }
            else -> {
                // Señal buena, resetear flags
                hasShownWarning = false
                hasShownCritical = false
                updateForegroundNotification("Señal estable", "$rssi dBm - Conexión buena")
            }
        }
    }

    private fun showProximityAlert(title: String, message: String, priority: Int, vibrate: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (vibrate) {
            builder.setVibrate(longArrayOf(0, 500, 250, 500, 250, 500))
        }

        notificationManager.notify(NOTIFICATION_ID + 1, builder.build())
    }

    private fun showDisconnectedNotification() {
        showProximityAlert(
            "Dispositivo Desconectado",
            "Se ha perdido la conexión con el dispositivo Bluetooth",
            NotificationCompat.PRIORITY_MAX,
            true
        )
    }

    private fun updateForegroundNotification(title: String, message: String) {
        val notification = createForegroundNotification(title, message)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createForegroundNotification(title: String, message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal para el servicio en primer plano
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Monitoreo Bluetooth",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación persistente para el monitoreo de dispositivos Bluetooth"
            }

            // Canal para alertas de proximidad
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Alertas de Proximidad",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas cuando te alejas del dispositivo Bluetooth"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun stopMonitoring() {
        isMonitoring = false
        handler.removeCallbacks(rssiRunnable)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothGatt?.disconnect()
                    bluetoothGatt?.close()
                }
            } else {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener monitoreo: ${e.message}")
        }
        
        bluetoothGatt = null
        currentDevice = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }
}