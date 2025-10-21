package com.example.bluetoothproximityalert

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_ENABLE_BT = 1
    }

    private lateinit var tvDeviceName: TextView
    private lateinit var tvRssiValue: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBarSignal: ProgressBar
    private lateinit var btnScanDevices: Button
    private lateinit var btnStartMonitoring: Button
    private lateinit var btnStopMonitoring: Button
    private lateinit var recyclerViewDevices: RecyclerView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var selectedDevice: BluetoothDevice? = null

    // Solicitud de múltiples permisos
    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { Log.d(TAG, "${it.key} = ${it.value}") }
        if (permissions.all { it.value }) {
            setupBluetooth()
        } else {
            Toast.makeText(this, "Se requieren todos los permisos", Toast.LENGTH_LONG).show()
        }
    }

    // BroadcastReceiver para descubrimiento de dispositivos
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            return
                        }
                    }
                    
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!discoveredDevices.contains(it) && it.name != null) {
                            discoveredDevices.add(it)
                            Log.d(TAG, "Dispositivo encontrado: ${it.name} - ${it.address}")
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        checkPermissions()
    }

    private fun initializeViews() {
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvRssiValue = findViewById(R.id.tvRssiValue)
        tvStatus = findViewById(R.id.tvStatus)
        progressBarSignal = findViewById(R.id.progressBarSignal)
        btnScanDevices = findViewById(R.id.btnScanDevices)
        btnStartMonitoring = findViewById(R.id.btnStartMonitoring)
        btnStopMonitoring = findViewById(R.id.btnStopMonitoring)
        recyclerViewDevices = findViewById(R.id.recyclerViewDevices)

        btnScanDevices.setOnClickListener { startDeviceDiscovery() }

        btnStartMonitoring.setOnClickListener {
            selectedDevice?.let { device ->
                startMonitoringService(device)
            }
        }

        btnStopMonitoring.setOnClickListener { stopMonitoringService() }
    }

    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            // Android 11 y anteriores
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestMultiplePermissions.launch(permissionsNeeded.toTypedArray())
        } else {
            setupBluetooth()
        }
    }

    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Este dispositivo no soporta Bluetooth", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        }

        // Registrar BroadcastReceiver
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)
    }

    private fun startDeviceDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Permiso de escaneo no otorgado", Toast.LENGTH_SHORT).show()
                return
            }
        }

        discoveredDevices.clear()
        tvStatus.text = "Estado: Escaneando dispositivos..."

        bluetoothAdapter?.startDiscovery()

        // Mostrar diálogo con dispositivos después de 10 segundos
        android.os.Handler(mainLooper).postDelayed({
            bluetoothAdapter?.cancelDiscovery()
            showDeviceSelectionDialog()
        }, 10000)
    }

    private fun showDeviceSelectionDialog() {
        if (discoveredDevices.isEmpty()) {
            Toast.makeText(this, "No se encontraron dispositivos", Toast.LENGTH_SHORT).show()
            tvStatus.text = "Estado: No se encontraron dispositivos"
            return
        }

        val deviceNames = discoveredDevices.map { device ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@map ""
                }
            }
            "${device.name} (${device.address})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Seleccionar Dispositivo")
            .setItems(deviceNames) { _, which ->
                selectedDevice = discoveredDevices[which]
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return@setItems
                    }
                }
                tvDeviceName.text = selectedDevice?.name ?: "Desconocido"
                tvStatus.text = "Estado: Dispositivo seleccionado"
                btnStartMonitoring.isEnabled = true
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startMonitoringService(device: BluetoothDevice) {
        val serviceIntent = Intent(this, BluetoothMonitorService::class.java).apply {
            action = BluetoothMonitorService.ACTION_START_MONITORING
            putExtra(BluetoothMonitorService.EXTRA_DEVICE_ADDRESS, device.address)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        tvStatus.text = "Estado: Monitoreando dispositivo"
        btnStartMonitoring.isEnabled = false
        btnStopMonitoring.isEnabled = true
        btnScanDevices.isEnabled = false
    }

    private fun stopMonitoringService() {
        val serviceIntent = Intent(this, BluetoothMonitorService::class.java).apply {
            action = BluetoothMonitorService.ACTION_STOP_MONITORING
        }
        startService(serviceIntent)

        tvStatus.text = "Estado: Monitoreo detenido"
        btnStartMonitoring.isEnabled = true
        btnStopMonitoring.isEnabled = false
        btnScanDevices.isEnabled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error al desregistrar receiver: ${e.message}")
        }
    }
}