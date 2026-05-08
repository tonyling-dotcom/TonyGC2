package com.example.tonygc2.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.tonygc2.data.Gc2DataParser
import com.example.tonygc2.data.ShotData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.util.*

class Gc2BluetoothManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    // Standard SPP UUID
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val GC2_DEVICE_NAME_PREFIX = "GC2"

    private var bluetoothSocket: BluetoothSocket? = null
    private var connectionJob: Job? = null
    private var readingJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _latestShotData = MutableStateFlow<ShotData?>(null)
    val latestShotData: StateFlow<ShotData?> = _latestShotData.asStateFlow()

    private val _rawBluetoothData = MutableStateFlow<String>("")
    val rawBluetoothData: StateFlow<String> = _rawBluetoothData.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothDevice>> = _availableDevices.asStateFlow()

    private val prefs = context.getSharedPreferences("gc2_prefs", Context.MODE_PRIVATE)
    private val SAVED_DEVICE_ADDRESS_KEY = "saved_device_address"

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device != null && device.name != null) {
                    val currentList = _availableDevices.value.toMutableList()
                    if (!currentList.any { it.address == device.address }) {
                        currentList.add(device)
                        _availableDevices.value = currentList
                    }
                    
                    // If we are auto-scanning for our saved device
                    val savedAddress = prefs.getString(SAVED_DEVICE_ADDRESS_KEY, null)
                    if (savedAddress != null && device.address == savedAddress) {
                        bluetoothAdapter?.cancelDiscovery()
                        connectToSavedDevice(device)
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(receiver, filter)
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Scanning : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    @SuppressLint("MissingPermission")
    fun startScanningForDevices() {
        if (!hasBluetoothPermissions()) return
        
        val pairedDevices = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        _availableDevices.value = pairedDevices

        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        bluetoothAdapter?.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun selectAndConnectDevice(device: BluetoothDevice) {
        prefs.edit().putString(SAVED_DEVICE_ADDRESS_KEY, device.address).apply()
        bluetoothAdapter?.cancelDiscovery()
        
        // Start the auto-connect loop which will handle connecting and reconnecting
        startAutoConnect()
    }

    @SuppressLint("MissingPermission")
    private fun connectToSavedDevice(device: BluetoothDevice) {
        coroutineScope.launch {
            try {
                _connectionState.value = ConnectionState.Connecting
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    device.createBond()
                    delay(3000)
                }
                connectToDevice(device)
            } catch (e: Exception) {
                Log.e("Gc2Bluetooth", "Connection error", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAutoConnect() {
        if (!hasBluetoothPermissions()) {
            _connectionState.value = ConnectionState.Error("Missing Bluetooth Permissions")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled or not supported")
            return
        }

        val savedAddress = prefs.getString(SAVED_DEVICE_ADDRESS_KEY, null)
        if (savedAddress == null) {
            _connectionState.value = ConnectionState.Disconnected
            // No saved device, start scanning to populate the dropdown
            startScanningForDevices()
            return
        }

        connectionJob?.cancel()
        connectionJob = coroutineScope.launch {
            while (isActive) {
                try {
                    val device = bluetoothAdapter?.getRemoteDevice(savedAddress)
                    if (device != null) {
                        _connectionState.value = ConnectionState.Connecting
                        // Ensure discovery is cancelled before attempting to connect
                        bluetoothAdapter?.cancelDiscovery()
                        connectToDevice(device)
                    } else {
                        _connectionState.value = ConnectionState.Error("Saved device not found")
                        delay(5000)
                    }
                } catch (e: Exception) {
                    Log.e("Gc2Bluetooth", "Connection error", e)
                    _connectionState.value = ConnectionState.Disconnected
                    // Wait before retrying to avoid spamming connection attempts
                    delay(5000) 
                }
                
                // If connectToDevice returns normally (e.g., disconnected gracefully), wait a bit before retrying
                delay(3000)
            }
        }
    }

    fun stopAutoConnect() {
        connectionJob?.cancel()
        readingJob?.cancel()
        closeSocket()
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        _connectionState.value = ConnectionState.Disconnected
    }

    @SuppressLint("MissingPermission")
    private fun findGc2Device(): BluetoothDevice? {
        val pairedDevices = bluetoothAdapter?.bondedDevices
        return pairedDevices?.find { it.name?.startsWith(GC2_DEVICE_NAME_PREFIX, ignoreCase = true) == true }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToDevice(device: BluetoothDevice) {
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothAdapter?.cancelDiscovery()
            bluetoothSocket?.connect()

            _connectionState.value = ConnectionState.Connected
            startReadingData()

            // Keep the connection alive as long as it's connected
            while (currentCoroutineContext().isActive && bluetoothSocket?.isConnected == true) {
                delay(1000)
            }
        } catch (e: Exception) {
            Log.e("Gc2Bluetooth", "Failed to connect to GC2", e)
            closeSocket()
            throw e
        }
    }

    private fun startReadingData() {
        readingJob?.cancel()
        readingJob = coroutineScope.launch {
            val inputStream: InputStream? = bluetoothSocket?.inputStream
            val buffer = ByteArray(1024)
            var stringBuilder = StringBuilder()

            while (isActive && inputStream != null) {
                try {
                    val bytes = inputStream.read(buffer)
                    if (bytes > 0) {
                        val incomingMessage = String(buffer, 0, bytes)
                        stringBuilder.append(incomingMessage)

                        // Handle different line endings (\r\n, \n, \r)
                        var endOfLineIndex = stringBuilder.indexOfAny(charArrayOf('\n', '\r'))
                        while (endOfLineIndex >= 0) {
                            val completeString = stringBuilder.substring(0, endOfLineIndex).trim()
                            
                            stringBuilder.delete(0, endOfLineIndex + 1)
                            // Remove any trailing \n or \r (e.g. if it was \r\n)
                            while(stringBuilder.isNotEmpty() && (stringBuilder[0] == '\n' || stringBuilder[0] == '\r')) {
                                stringBuilder.deleteCharAt(0)
                            }
                            
                            if (completeString.isNotEmpty()) {
                                handleIncomingData(completeString)
                            }
                            endOfLineIndex = stringBuilder.indexOfAny(charArrayOf('\n', '\r'))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Gc2Bluetooth", "Error reading data", e)
                    break
                }
            }
            // If we break out of the loop, connection is lost
            closeSocket()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun handleIncomingData(data: String) {
        Log.d("Gc2Bluetooth", "Received: $data")
        _rawBluetoothData.value = data
        val parsedData = Gc2DataParser.parse(data)
        if (parsedData != null) {
            _latestShotData.value = parsedData
        }
    }

    private fun closeSocket() {
        try {
            bluetoothSocket?.close()
        } catch (e: Exception) {
            Log.e("Gc2Bluetooth", "Error closing socket", e)
        }
        bluetoothSocket = null
    }

    private fun hasBluetoothPermissions(): Boolean {
        val hasConnect = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
        return hasConnect
    }
}
