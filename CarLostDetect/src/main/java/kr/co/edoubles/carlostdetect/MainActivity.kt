package kr.co.edoubles.carlostdetect

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kr.co.edoubles.carlostdetect.databinding.ActivityMainBinding
import kr.co.edoubles.carlostdetect.service.CarLostDetectService
import kr.co.edoubles.carlostdetect.service.ServiceListener
import kr.co.edoubles.carlostdetect.utils.PermissionUtil
import org.tensorflow.lite.task.vision.detector.Detection
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack


class MainActivity : AppCompatActivity(), ActivityCompat.OnRequestPermissionsResultCallback, ServiceListener {
    private lateinit var binding: ActivityMainBinding

    private lateinit var mainPreferences: SharedPreferences

    private lateinit var eglBase: EglBase

    private lateinit var videoTrack: VideoTrack
    private lateinit var videoSink: VideoSink
    private var isActive: Boolean = false

    /**
     * CarLostDetectService 와 Bind 하는데 사용하는 변수들
     *
     * carLostDetectService, isBound, connection
     */
    private var carLostDetectService = CarLostDetectService()
    private var isBound = false;
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder: CarLostDetectService.CarLostDetectServiceBinder =
                service as CarLostDetectService.CarLostDetectServiceBinder
            carLostDetectService = binder.service
            isBound = true

            carLostDetectService.setIsActive(isActive)
            carLostDetectService.setVideoTrackListener(this@MainActivity)
            startService()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater).also {
            setContentView(it.root)
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mainPreferences = getSharedPreferences("SharedPreferences_main", MODE_PRIVATE);

        // CarLostDetectService를 정지시키는 Button 동작
        binding.btnStop.setOnClickListener {
            if (::videoTrack.isInitialized) {
                videoTrack.removeSink(videoSink)
            }
            stopService()
        }

        // 분실물 감지 알림을 활성/비활성화 시키는 Switch 동작
        binding.switchLostItemDetection.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isActive = true
                carLostDetectService.setIsActive(isActive)
                mainPreferences.edit {
                    putBoolean("isActive", isActive)
                }
            } else {
                isActive = false
                carLostDetectService.setIsActive(isActive)
                mainPreferences.edit {
                    putBoolean("isActive", isActive)
                }
            }
        }

        // EglBase 객체 초기화
        EglBaseManager.setMainActivityState(true)
        eglBase = EglBaseManager.getEglBaseInstance()
        // SurfaceViewRenderer 초기화
        initSurfaceViewRenderer(binding.cameraLayout)
    }

    override fun onResume() {
        super.onResume()

        isActive = mainPreferences.getBoolean("isActive", false)
        binding.switchLostItemDetection.setChecked(isActive)

        videoSink = binding.cameraLayout

        // 배터리 최적화 기능 비활성화
        ignoreBatteryOptimizations()
        // CarLostDetectService와 Binding하여 Service 실행
        permissionCheck(this)
    }

    /**
     * 배터리 최적화 기능을 비활성 시키는 함수
     */
    private fun ignoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Log.d("BatteryOpt", "Battery optimization dialog triggered.")
            } else {
                Log.d("BatteryOpt", "Battery optimization already ignored.")
            }
        }
    }

    /**
     * SurfaceViewRenderer를 초기화시키는 함수
     */
    private fun initSurfaceViewRenderer(layout: SurfaceViewRenderer) {
        layout.init(eglBase.eglBaseContext, null)
        layout.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        layout.setZOrderMediaOverlay(false)
    }

    private fun permissionCheck(context: Context) {
        val permissionList = listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.POST_NOTIFICATIONS)

        if (!PermissionUtil.checkPermission(context, permissionList)) {
            PermissionUtil.requestPermission(context as Activity, permissionList)
        } else {
            // Bind To CarLostDetectService
            bindToService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        var flag = true
        if (grantResults.isNotEmpty()) {
            for ((i, _) in permissions.withIndex()) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    flag = false
                }
            }

            if (flag) {
                // Bind To WebRTCService
                bindToService()
            } else {
                Log.e(TAG, "권한이 필요 합니다")
            }
        }
    }

    /**
     * CarLostDetectService와 Bind 하는 메소드
     */
    private fun bindToService() {
        try {
            val intent = Intent(this, CarLostDetectService::class.java)
            bindService(intent, connection, BIND_AUTO_CREATE)
            Log.d(TAG, "Bind with Service Success")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * CarLostDetectService를 실행시키는 메소드
     */
    private fun startService() {
        if (isBound) {
//            carLostDetectService.processCommand(resources.getString(R.string.client_id))
            val serviceIntent = Intent(this, CarLostDetectService::class.java);
            startService(serviceIntent);
        }
    }

    /**
     * CarLostDetectService를 종료시키는 메소드
     */
    private fun stopService() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }

        val serviceIntent = Intent(this, CarLostDetectService::class.java)
        stopService(serviceIntent)
    }


    /**
     * VideoTrackListener Interface Method
     */
    override fun onVideoTrackReceived(videoTrack: VideoTrack) {
        this.videoTrack = videoTrack
        videoTrack.addSink(videoSink)
    }

    override fun setLostString(lost: String?) {
        CoroutineScope(Dispatchers.Main).launch {
            binding.txtLost.setText("두고 간 물건 : $lost")
        }
    }

    override fun drawCustomBox(
        results: MutableList<Detection>,
        imageHeight: Int,
        imageWidth: Int
    ) {
        binding.customBoxView.setResults(
            results,
            imageHeight,
            imageWidth
        )
    }

    override fun invalidateCustomBox() {
        binding.customBoxView.invalidate()
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onDestroy() {
        binding.customBoxView.clear()
        binding.cameraLayout.release()
        EglBaseManager.setMainActivityState(false);

        if (isBound) {
            unbindService(connection)
            isBound = false
        }

        if (::videoTrack.isInitialized) {
            videoTrack.removeSink(videoSink)
        }

        super.onDestroy()
    }
}