package dev.camouse

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    companion object {
        // Change these if the cursor moves in the opposite direction.
        private const val INVERT_X = false
        private const val INVERT_Y = false
        private const val PERMISSIONS_REQUEST = 1
        private const val DISCOVERABLE_REQUEST = 2
        private val REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01,
            0x09, 0x01, 0xA1.toByte(), 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
            0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01,
            0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
            0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x02,
            0x81.toByte(), 0x06, 0xC0.toByte(), 0xC0.toByte()
        )
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val tracker = MotionTracker()
    private lateinit var adapter: BluetoothAdapter
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var running = false
    private var torch = false
    private var sensitivity = 2.0f
    private var residualX = 0f
    private var residualY = 0f
    private var focusProgress = 0
    private var maximumFocusDistance = 0f
    private lateinit var hidStatus: TextView
    private lateinit var hostStatus: TextView
    private lateinit var cameraStatus: TextView
    private lateinit var motionStatus: TextView
    private lateinit var startButton: Button
    private lateinit var torchButton: Button
    private lateinit var sensitivityStatus: TextView
    private lateinit var focusStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        buildUi()
        requestNeededPermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun label(value: String) = TextView(this).also { it.text = value; it.textSize = 18f; root.addView(it) }
        fun button(value: String, action: () -> Unit) = Button(this).also { it.text = value; root.addView(it); it.setOnClickListener { action() } }
        hidStatus = label("HID: 準備中")
        hostStatus = label("接続先: なし")
        cameraStatus = label("Camera: 停止")
        motionStatus = label("dx: 0  dy: 0  confidence: 0")
        button("PCから見えるようにする (120秒)") { requestDiscoverable() }
        button("ペア済みPCへ接続") { connectFirstBonded() }
        button("HIDテスト: 右へ10") { sendMotion(10, 0) }
        startButton = button("Start") { if (running) stopCamera() else startCamera() }
        torchButton = button("Torch ON") { torch = !torch; camera?.cameraControl?.enableTorch(torch); torchButton.text = if (torch) "Torch OFF" else "Torch ON" }
        sensitivityStatus = label("Sensitivity: 2.0")
        root.addView(SeekBar(this).apply {
            max = 80; progress = 15
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    sensitivity = (progress + 5) / 10f
                    sensitivityStatus.text = "Sensitivity: %.1f".format(sensitivity)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
        focusStatus = label("Focus: Auto")
        root.addView(SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    focusProgress = progress
                    applyFocus()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
        setContentView(root)
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    else arrayOf(Manifest.permission.CAMERA)

    private fun hasPermissions() = requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requestNeededPermissions() {
        if (!hasPermissions()) requestPermissions(requiredPermissions(), PERMISSIONS_REQUEST)
        else initHid()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST) {
            if (hasPermissions()) initHid() else hidStatus.text = "HID: Camera/近くのデバイス権限が必要"
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            val sdp = BluetoothHidDeviceAppSdpSettings("Camouse", "Camera mouse PoC", "Camouse", BluetoothHidDevice.SUBCLASS1_MOUSE, REPORT_DESCRIPTOR)
            val ok = hid?.registerApp(sdp, null, null, mainExecutor, object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    hidStatus.text = if (registered) "HID: 登録済み" else "HID: 登録解除/失敗"
                    host = if (registered) pluggedDevice else null
                    hostStatus.text = "接続先: ${host?.name ?: "なし"}"
                }
                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                    if (state == BluetoothProfile.STATE_CONNECTED) host = device
                    else if (host == device) host = null
                    hostStatus.text = "接続先: ${host?.name ?: "なし"} (${state})"
                }
            }) ?: false
            if (!ok) hidStatus.text = "HID: registerApp失敗 (端末のHID Device対応を確認)"
        }
        override fun onServiceDisconnected(profile: Int) {
            hid = null; host = null; hidStatus.text = "HID: サービス切断"; hostStatus.text = "接続先: なし"
        }
    }

    private fun initHid() {
        if (!adapter.isEnabled) { hidStatus.text = "HID: Bluetoothを設定からONにしてください"; return }
        if (!adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE))
            hidStatus.text = "HID: HID Device profileを取得できません"
    }

    private fun requestDiscoverable() {
        if (!hasPermissions()) { requestNeededPermissions(); return }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120), DISCOVERABLE_REQUEST)
    }

    private fun connectFirstBonded() {
        if (!hasPermissions()) { requestNeededPermissions(); return }
        val devices = adapter.bondedDevices.toList()
        if (devices.isEmpty()) { hostStatus.text = "接続先: ペア済み機器なし。PCからペアリングしてください"; return }
        AlertDialog.Builder(this).setTitle("接続先を選択")
            .setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, index ->
                val device = devices[index]
                hostStatus.text = "接続先: ${device.name} に接続中"
                if (hid?.connect(device) != true) hostStatus.text = "接続先: connect失敗"
            }.show()
    }

    private fun sendMotion(dx: Int, dy: Int) {
        val device = host ?: return
        val x = dx.coerceIn(-127, 127).toByte()
        val y = dy.coerceIn(-127, 127).toByte()
        if (x.toInt() != 0 || y.toInt() != 0) hid?.sendReport(device, 0, byteArrayOf(0, x, y))
    }

    private fun startCamera() {
        if (!hasPermissions()) { requestNeededPermissions(); return }
        if (running) return
        running = true; startButton.text = "Stop"; cameraStatus.text = "Camera: 起動中"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (!running) return@addListener
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(android.util.Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()).build()
                analysis.setAnalyzer(cameraExecutor) { image -> analyze(image) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                camera?.cameraControl?.enableTorch(torch)
                maximumFocusDistance = Camera2CameraInfo.from(camera!!.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                applyFocus()
                cameraStatus.text = "Camera: 動作中"
            } catch (e: Exception) { stopCamera(); cameraStatus.text = "Camera: ${e.message}" }
        }, mainExecutor)
    }

    private fun applyFocus() {
        val currentCamera = camera
        if (currentCamera == null) {
            focusStatus.text = if (focusProgress == 0) "Focus: Auto" else "Focus: カメラ起動後に適用"
            return
        }
        if (maximumFocusDistance <= 0f) {
            focusStatus.text = "Focus: このレンズは手動調整不可"
            return
        }
        val options = CaptureRequestOptions.Builder()
        // Optical stabilization deliberately hides small camera motions from the tracker.
        options.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        options.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (focusProgress == 0) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            focusStatus.text = "Focus: Auto"
        } else {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, maximumFocusDistance * focusProgress / 100f)
            focusStatus.text = "Focus: 近距離 ${focusProgress}%"
        }
        Camera2CameraControl.from(currentCamera.cameraControl).setCaptureRequestOptions(options.build())
        tracker.reset()
    }

    private fun analyze(image: ImageProxy) {
        try {
            val rotation = image.imageInfo.rotationDegrees
            val result = tracker.analyze(image)
            if (result != null) runOnUiThread {
                if (!running) return@runOnUiThread
                motionStatus.text = "dx: ${result.dx}  dy: ${result.dy}  confidence: %.2f  ${result.detail}".format(result.confidence)
                if (result.confidence >= 0.06f) {
                    // The desk image moves opposite to the phone. ImageProxy rotation maps sensor to display.
                    val (sx, sy) = when (rotation) {
                        90 -> -result.dy to result.dx
                        180 -> -result.dx to -result.dy
                        270 -> result.dy to -result.dx
                        else -> result.dx to result.dy
                    }
                    residualX += (if (INVERT_X) sx else -sx) * sensitivity
                    residualY += (if (INVERT_Y) sy else -sy) * sensitivity
                    val x = if (abs(residualX) >= 1.5f) residualX.roundToInt().coerceIn(-127, 127) else 0
                    val y = if (abs(residualY) >= 1.5f) residualY.roundToInt().coerceIn(-127, 127) else 0
                    residualX -= x; residualY -= y
                    sendMotion(x, y)
                } else { residualX = 0f; residualY = 0f }
            }
        } finally { image.close() }
    }

    private fun stopCamera() {
        running = false; tracker.reset(); residualX = 0f; residualY = 0f
        cameraProvider?.unbindAll(); camera = null
        startButton.text = "Start"; cameraStatus.text = "Camera: 停止"
    }

    override fun onDestroy() {
        stopCamera(); hid?.unregisterApp(); hid?.let { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        cameraExecutor.shutdown(); super.onDestroy()
    }
}
