package com.micguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID

/**
 * Mic Guard phone display.
 *   GREEN = in a call, mic LIVE     RED = in a call, mic MUTED     BLACK = no call
 *   Tap the screen during a call to mute / unmute the laptop mic.
 *   Long-press to switch demo mode on/off (tap cycles the colours in demo mode).
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    companion object {
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val REQ_PERMS = 42
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var label: TextView
    private lateinit var sub: TextView

    private var gatt: BluetoothGatt? = null
    private var ch: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var connected = false
    private var demo = false
    private var state = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        label = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        sub = TextView(this).apply {
            setTextColor(Color.argb(220, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 0)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(label)
            addView(sub)
            setOnClickListener { onTap() }
            setOnLongClickListener { toggleDemo(); true }
        }
        setContentView(root)
        render(0)
        sub.text = "Starting…"
        ensurePermissionsThenScan()
    }

    // ---------- UI ----------
    private fun render(v: Int) {
        state = v
        when (v) {
            1 -> { root.setBackgroundColor(0xFF0A8F2F.toInt()); label.text = "MIC LIVE"; sub.text = "You are being heard · tap to mute" }
            2 -> { root.setBackgroundColor(0xFFC0141C.toInt()); label.text = "MUTED"; sub.text = "Safe to talk privately · tap to unmute" }
            else -> { root.setBackgroundColor(Color.BLACK); label.text = "NO CALL"; sub.text = if (connected) "Connected · waiting for a call" else "Searching for your laptop…" }
        }
    }

    private fun buzz(ms: Long) {
        val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun setStatus(text: String) = ui.post { sub.text = text }

    private fun toggleDemo() {
        demo = !demo
        if (demo) { render(1); sub.text = "DEMO · tap to cycle colours · long-press to exit" }
        else render(if (connected) state else 0)
    }

    private fun onTap() {
        when {
            demo -> render((state + 1) % 3)
            connected && ch != null && state != 0 -> { sendToggle(); buzz(60) }
            connected -> setStatus("No active call detected")
            else -> startScan()
        }
    }

    // ---------- Permissions ----------
    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermissionsThenScan() {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startScan() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
            else sub.text = "Bluetooth permission is required · tap to retry"
        }
    }

    // ---------- Scan / connect ----------
    private val scanTimeout = Runnable {
        stopScan()
        if (!connected) ui.postDelayed({ startScan() }, 1000)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return   // ignore extra results delivered after the first hit
            stopScan()
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setStatus("Scan failed ($errorCode) · tap to retry")
        }
    }

    private fun startScan() {
        if (scanning || connected || demo) return
        if (neededPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            ensurePermissionsThenScan(); return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { setStatus("Turn on Bluetooth · tap to retry"); return }
        val scanner = adapter.bluetoothLeScanner ?: run { setStatus("Bluetooth LE unavailable"); return }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        setStatus("Searching for your laptop…")
        scanner.startScan(listOf(filter), settings, scanCb)
        ui.postDelayed(scanTimeout, 15000)
    }

    private fun stopScan() {
        ui.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {}
    }

    private fun connect(device: BluetoothDevice) {
        setStatus("Connecting…")
        gatt = device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else {
                connected = false
                ch = null
                g.close()
                gatt = null
                ui.post {
                    if (!demo) { render(0); sub.text = "Disconnected · searching…" }
                    ui.postDelayed({ startScan() }, 1500)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(SERVICE)?.getCharacteristic(CHAR)
            if (c == null) { setStatus("Mic Guard service not found on this device"); g.disconnect(); return }
            ch = c
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            connected = true
            ch?.let { g.readCharacteristic(it) }
            ui.post { if (!demo && state == 0) render(0) }
        }

        // Android 13+ callbacks
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            handle(value)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            handle(value)

        // Android 12 and older callbacks
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            handle(c.value ?: ByteArray(0))

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) =
            handle(c.value ?: ByteArray(0))
    }

    private fun handle(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val v = bytes[0].toInt()
        if (v !in 0..2) return
        ui.post {
            if (demo) return@post
            val previous = state
            render(v)
            if (v == 1 && previous != 1) buzz(300)   // mic just went live
        }
    }

    // ---------- Tap -> toggle laptop mic ----------
    private fun sendToggle() {
        val g = gatt ?: return
        val c = ch ?: return
        val payload = byteArrayOf(1)
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            c.value = payload
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    override fun onDestroy() {
        stopScan()
        ui.removeCallbacksAndMessages(null)
        gatt?.close()
        gatt = null
        super.onDestroy()
    }
}
