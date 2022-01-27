package com.redc4ke.vehicleapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.slider.Slider
import com.redc4ke.vehicleapp.databinding.ActivityMainBinding
import java.lang.Exception
import java.util.*


// Duration of BLE scan
const val SCAN_PERIOD: Long = 10000
// Characteristic write interval (ms)
const val WRITE_INTERVAL: Long = 200

const val DEVICE_NAME = "DaddyMobile"
private val SERVICE_UUID = UUID.fromString("00002137-0000-1000-8000-00805F9B34FB")
private val TURN_CHARACTERISTIC_UUID = UUID.fromString("00006901-0000-1000-8000-00805F9B34FB")
private val MOVEMENT_CHARACTERISTIC_UUID = UUID.fromString("00006902-0000-1000-8000-00805F9B34FB")

class MainActivity : AppCompatActivity() {

    private lateinit var btManager: BluetoothManager
    private lateinit var btAdapter: BluetoothAdapter
    private lateinit var btScanner: BluetoothLeScanner

    private val handler = Handler(Looper.getMainLooper())
    private var btGatt: BluetoothGatt? = null
    private var connectionState = false
    private var scanning = false

    private var turningCharacteristic: BluetoothGattCharacteristic? = null
    private var movementCharacteristic: BluetoothGattCharacteristic? = null
    private var nextCharacteristic = turningCharacteristic

    private val locationPermission =
        if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACCESS_FINE_LOCATION
        else Manifest.permission.ACCESS_COARSE_LOCATION

    private val updateBlock: Runnable = Runnable {
        // Do movement & turn characteristic write repeatedly
        run {
            if (connectionState) {
                try {
                    btGatt?.writeCharacteristic(
                        nextCharacteristic ?: turningCharacteristic ?: return@Runnable
                    )
                    Log.d("debug", "Writing!")

                    // Post another execution
                    handler.postDelayed(updateBlock, WRITE_INTERVAL)
                    // Switch to another main characteristic
                    nextCharacteristic =
                        if (nextCharacteristic == movementCharacteristic)
                            turningCharacteristic
                        else
                            movementCharacteristic

                } catch (e: SecurityException) {
                    Log.w("debug", "App doesn't have Bluetooth permissions!")
                }
            }
        }
    }

    // Check for Bluetooth permissions
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                permsGranted()
            } else {
                Toast.makeText(
                    applicationContext,
                    "Grant Bluetooth permissions!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // Callback for GATT conection
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("debug", "Connected to GATT server")
                btGatt = gatt
                connectionState = true

                try {
                    // Get GATT services
                    btGatt?.discoverServices()

                } catch (e: SecurityException) {
                    Log.w("debug", "App doesn't have Bluetooth permissions!")
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Scan again
                if (!scanning) scan(btScanner)

                Log.d("debug", "Disconnected!")
                btGatt = gatt
                connectionState = false

            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("debug", "Discovered services: " + gatt?.services.toString())

                // Get control service and initialize characteristics
                val controlService = gatt?.getService(SERVICE_UUID)
                turningCharacteristic = controlService?.getCharacteristic(
                    TURN_CHARACTERISTIC_UUID
                )
                movementCharacteristic = controlService?.getCharacteristic(
                    MOVEMENT_CHARACTERISTIC_UUID
                )

                if (turningCharacteristic != null && movementCharacteristic != null) {
                    Log.d("debug", "Found the characteristics!")

                    // Start updating!
                    handler.post(updateBlock)

                } else {
                    Log.w("debug", "Characteristics not found!")
                }

            } else {
                Log.w("debug", "onServicesDiscovered received: $status")
            }
        }
    }

    // Callback for BLE scan
    private val btScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            if (result != null) {
                try {
                    // Stop the scan
                    if (scanning) scan(btScanner)

                    // Connect
                    result.device.connectGatt(applicationContext, true, gattCallback)

                } catch (e: SecurityException) {
                    Log.w("debug", "App doesn't have Bluetooth permissions!")
                }
            }
        }
    }

    // TODO: temporal
    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissionLauncher.launch(locationPermission)

        binding.turnSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
            }

            override fun onStopTrackingTouch(slider: Slider) {
                // Reset slider to neutral value
                slider.value = 101f
            }
        })
        binding.turnSlider.addOnChangeListener { _, value, _ ->
            turningCharacteristic?.value = byteArrayOf(value.toInt().toByte())
        }

        binding.moveSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
            }

            override fun onStopTrackingTouch(slider: Slider) {
                // Reset slider to neutral value
                slider.value = 101f
            }
        })
        binding.moveSlider.addOnChangeListener { _, value, _ ->
            movementCharacteristic?.value = byteArrayOf(value.toInt().toByte())
        }
    }

    private fun permsGranted() {
        btManager =
            applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter
        btScanner = btAdapter.bluetoothLeScanner

        scan(btScanner)
    }

    private fun scan(scanner: BluetoothLeScanner) {
        val scanFilters =
            listOf(
                ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
            )
        val scanSettings = ScanSettings
            .Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        try {
            if (!scanning) {
                // Post a scan stop
                Handler(Looper.getMainLooper()).postDelayed({
                    scanning = false
                    scanner.stopScan(btScanCallback)
                    Log.d("debug", "Stopped Scanning!")
                }, SCAN_PERIOD)
                scanning = true
                scanner.startScan(scanFilters, scanSettings, btScanCallback)
                Log.d("debug", "Started Scanning!")
            } else {
                scanning = false
                scanner.stopScan(btScanCallback)
                Log.d("debug", "Stopped Scanning!")
            }
        } catch (e: SecurityException) {
            Log.w("debug", "App doesn't have Bluetooth permissions!")
        }
    }

}