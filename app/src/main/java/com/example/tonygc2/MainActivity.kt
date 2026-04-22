package com.example.tonygc2

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.tonygc2.bluetooth.Gc2BluetoothManager
import com.example.tonygc2.data.ShotData
import com.example.tonygc2.ui.theme.TonyGC2Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothManager: Gc2BluetoothManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bluetoothManager.startAutoConnect()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on while app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bluetoothManager = Gc2BluetoothManager(this)

        setContent {
            TonyGC2Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    val connectionState by bluetoothManager.connectionState.collectAsState()
                    val shotData by bluetoothManager.latestShotData.collectAsState()
                    val availableDevices by bluetoothManager.availableDevices.collectAsState()
                    var expanded by remember { mutableStateOf(false) }

                    Box(modifier = Modifier.fillMaxSize()) {
                        ShotDisplayScreen(shotData)
                        
                        // Small connection status indicator and dropdown at the top right
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = getConnectionStatusText(connectionState),
                                color = getConnectionStatusColor(connectionState),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            Box {
                                Button(onClick = {
                                    expanded = true
                                    bluetoothManager.startScanningForDevices()
                                }) {
                                    Text("Select Device")
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    if (availableDevices.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("Scanning...") },
                                            onClick = { }
                                        )
                                    } else {
                                        availableDevices.forEach { device ->
                                            @SuppressLint("MissingPermission")
                                            val deviceName = device.name ?: device.address
                                            DropdownMenuItem(
                                                text = { Text(deviceName) },
                                                onClick = {
                                                    expanded = false
                                                    bluetoothManager.selectAndConnectDevice(device)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothManager.stopAutoConnect()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            bluetoothManager.startAutoConnect()
        }
    }

    private fun getConnectionStatusText(state: Gc2BluetoothManager.ConnectionState): String {
        return when (state) {
            is Gc2BluetoothManager.ConnectionState.Connected -> "Connected"
            is Gc2BluetoothManager.ConnectionState.Connecting -> "Connecting..."
            is Gc2BluetoothManager.ConnectionState.Scanning -> "Scanning for GC2..."
            is Gc2BluetoothManager.ConnectionState.Disconnected -> "Disconnected"
            is Gc2BluetoothManager.ConnectionState.Error -> "Error: ${state.message}"
        }
    }

    private fun getConnectionStatusColor(state: Gc2BluetoothManager.ConnectionState): Color {
        return when (state) {
            is Gc2BluetoothManager.ConnectionState.Connected -> Color.Green
            is Gc2BluetoothManager.ConnectionState.Connecting,
            is Gc2BluetoothManager.ConnectionState.Scanning -> Color.Yellow
            is Gc2BluetoothManager.ConnectionState.Disconnected,
            is Gc2BluetoothManager.ConnectionState.Error -> Color.Red
        }
    }
}

@Composable
fun ShotDisplayScreen(shotData: ShotData?) {
    // 3 equal-width vertical columns
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Carry Distance
        MetricBox(
            modifier = Modifier.weight(1f),
            label = "CARRY",
            value = shotData?.carryDistance?.toString() ?: "--",
            unit = "YARDS"
        )

        // Total Distance
        MetricBox(
            modifier = Modifier.weight(1f),
            label = "TOTAL",
            value = shotData?.totalDistance?.toString() ?: "--",
            unit = "YARDS"
        )

        // Offline
        MetricBox(
            modifier = Modifier.weight(1f),
            label = "OFFLINE",
            value = shotData?.offline ?: "--",
            unit = "YARDS"
        )
    }
}

@Composable
fun MetricBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = value,
            color = Color(0xFF39FF14), // Neon Green
            fontSize = 120.sp, // Large text for visibility from a distance
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            lineHeight = 120.sp
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = unit,
            color = Color.LightGray,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
