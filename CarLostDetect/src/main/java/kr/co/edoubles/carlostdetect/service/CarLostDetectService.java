package kr.co.edoubles.carlostdetect.service;

import static kr.co.edoubles.carlostdetect.utils.ConstantsKt.CAPTURE_FPS;
import static kr.co.edoubles.carlostdetect.utils.ConstantsKt.CAPTURE_HEIGHT;
import static kr.co.edoubles.carlostdetect.utils.ConstantsKt.CAPTURE_WIDTH;
import static kr.co.edoubles.carlostdetect.utils.ConstantsKt.DETECT_INTERVAL;
import static kr.co.edoubles.carlostdetect.utils.ConstantsKt.FOREGROUND_CHANNEL_ID;
import static kr.co.edoubles.carlostdetect.utils.ConstantsKt.FOREGROUND_ID;
import static kr.co.edoubles.carlostdetect.utils.ConstantsKt.LOCAL_VIDEO_TRACK_ID;

import android.Manifest;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.tensorflow.lite.task.vision.detector.Detection;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import kr.co.edoubles.awsiotmqtt.AWSIotMqtt;
import kr.co.edoubles.awsiotmqtt.OnMqttCallback;
import kr.co.edoubles.carlostdetect.EglBaseManager;
import kr.co.edoubles.carlostdetect.R;
import kr.co.edoubles.carlostdetect.alert.AlertHelper;
import kr.co.edoubles.carlostdetect.detect.DetectorListener;
import kr.co.edoubles.carlostdetect.detect.ObjectDetectStatus;
import kr.co.edoubles.carlostdetect.detect.ObjectDetectorHelper;
import kr.co.edoubles.carlostdetect.utils.BoardingStatus;
import kr.co.edoubles.webrtc.OnPeerCallback;
import kr.co.edoubles.webrtc.PeerManager;

public class CarLostDetectService extends Service {

    private static final String TAG = "CarLostDetectService";

    // Preview 및 WebRTC 기능을 위한 변수들
    private VideoCapturer videoCapturer;
    private EglBase eglBase;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private VideoSink videoSink;


    // AWS MQTT 를 위한 변수들
    private AWSIotMqtt awsIotMqtt;
    private PeerManager peerManager;

    // Listener 및 Helper Class
    private ServiceListener listener;
    private ObjectDetectorHelper objectDetectorHelper;
    private AlertHelper alertHelper;

    private ExecutorService executorService;

    // 물체 식별 Logic에 필요한 변수들
    private ObjectDetectStatus detectorStatus = ObjectDetectStatus.NOT_DETECT;
    private String objectLabels = "";
    private ArrayList<String> excludeLabels;
    private ArrayList<String> beforeList = new ArrayList<>();
    private ArrayList<String> afterList = new ArrayList<>();

    // 상태 값들
    private boolean isProcessing = false;
    private boolean personCheck = false;
    private boolean isActive = false;

    // CAN이 물린 후 사용할 Logic에 사용될 변수들
    private BoardingStatus status = BoardingStatus.BEFORE;
    private boolean door = false;
    private boolean seat = false;
    private boolean human = false;
    private boolean isDeleted = true;

    // AVN 에서 CAN 신호를 받기 위한 BroadCastReceiver
    private BroadcastReceiver doorBroadcastReceiver;
    private BroadcastReceiver seatBroadcastReceiver;

    /**
     * Activity 와 Bind 하기위해 사용하는 변수와 클래스
     * <p>
     * binder, CarLostDetectServiceBinder
     */
    private final IBinder binder = new CarLostDetectServiceBinder();

    public class CarLostDetectServiceBinder extends Binder {
        public CarLostDetectService getService() {
            return CarLostDetectService.this;
        }
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "---onCreate()---");

        if (isDeleted) {
            init();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enrollReceiver();
        }
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "---onStartCommand()---");

        if (isDeleted) {
            init();
        }

        processCommand(getResources().getString(R.string.client_id));
//        settingCamera();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "---onDestroy()---");

        delete();
        // 리시버 해제
        unregisterReceiver(doorBroadcastReceiver);

        super.onDestroy();
    }

    private void init() {
        createNotificationChannel();
        startForeground();

        executorService = Executors.newSingleThreadExecutor();

        EglBaseManager.setServiceState(true);
        eglBase = EglBaseManager.getEglBaseInstance();

        videoSink = new VideoSink() {
            @Override
            public void onFrame(VideoFrame videoFrame) {
                detectObject(videoFrame);
            }
        };
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());

        initDetector();
        initAlertHelper();
        initExcludedLabels();

        status = BoardingStatus.BEFORE;
        door = false;
        seat = false;
        human = false;

        isDeleted = false;
    }

    private void initDetector() {
        objectDetectorHelper = new ObjectDetectorHelper(0.5f, 5, this, new objectDetectorListener());
    }

    private void initAlertHelper() {
        alertHelper = new AlertHelper(getApplicationContext());
    }

    private void initExcludedLabels() {
        String[] tmp = getResources().getStringArray(R.array.exclude_labels);
        excludeLabels = new ArrayList<>(Arrays.asList(tmp));
    }

    public void processCommand(String name) {
        String myName = name;
        if (myName == null) {
            Log.e(TAG, "MyName is null");
            return;
        }

        if (awsIotMqtt == null) {
            awsIotMqtt = new AWSIotMqtt(this, new AWSCallback(myName));
        }
        if (peerManager == null) {
            peerManager = new PeerManager(getApplicationContext(), eglBase, new PeerCallback(myName));
            createMediaStream();
        }

        if (listener != null) {
            listener.onVideoTrackReceived(localVideoTrack);
        }

        try {
            awsIotMqtt.prepareKeyStore();
            awsIotMqtt.connect();
        } catch (Exception e) {
            Log.e(TAG, "AWSKeyStore 준비 또는 AWSIoTMQTT 연결 중 오류 발생", e);
        }
    }


    private void delete() {
        stopForeground(true);

        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                Log.e(TAG, "비디오 캡처 중지 중 오류 발생", e);
            } finally {
                // SurfaceTextureHelper 및 VideoCapturer는 stopCapture 후에 해제해야 함
                if (surfaceTextureHelper != null) {
                    surfaceTextureHelper.dispose();
                    surfaceTextureHelper = null;
                    Log.d(TAG, "SurfaceTextureHelper 리소스 해제됨");
                }
                videoCapturer.dispose();
                videoCapturer = null;
                Log.d(TAG, "VideoCapturer 리소스 해제됨");
            }
        }
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
            Log.d(TAG, "ExecutorService 종료됨");
        }
        objectDetectorHelper.clearObjectDetector();
        alertHelper.release();
        if (objectDetectorHelper != null) {
            objectDetectorHelper.clearObjectDetector();
            Log.d(TAG, "ObjectDetectorHelper 리소스 해제됨");
        }
        if (alertHelper != null) {
            alertHelper.release();
            Log.d(TAG, "ToneAlertHelper 리소스 해제됨");
        }
        EglBaseManager.setServiceState(false);
        Log.d(TAG, "EglBaseManager 상태 해제됨");

        if (localVideoTrack != null) {
            localVideoTrack.setEnabled(false);
            localVideoTrack.removeSink(videoSink);
            localVideoTrack = null;
            Log.d(TAG, "LocalVideoTrack 리소스 해제됨");
        }

        if (awsIotMqtt != null) {
            awsIotMqtt.disconnect();
            awsIotMqtt = null;
            Log.d(TAG, "AWSIotMqtt 연결 해제됨");
        }
        if (peerManager != null) {
            peerManager.deleteAllPeers();
            peerManager.deleteAllDataChannels();
            peerManager = null;
            Log.d(TAG, "PeerManager 리소스 해제됨");
        }

        isDeleted = true;
        Log.d(TAG, "서비스 리소스 해제 과정 종료");
    }

    /**
     * @param intent The Intent that was used to bind to this service,
     *               as given to {@link android.content.Context#bindService
     *               Context.bindService}.  Note that any extras that were included with
     *               the Intent at that point will <em>not</em> be seen here.
     * @return binder
     */
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "---onTaskRemoved()---");
//        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    public void setIsActive(boolean isActive) {
        this.isActive = isActive;
        Log.d(TAG, "---Got Active Data From MainActivity : " + this.isActive + "---");
    }

    public void setVideoTrackListener(ServiceListener listener) {
        this.listener = listener;
    }

    /**
     * Notification Channel 등록 메소드
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    FOREGROUND_CHANNEL_ID,                       // 채널 ID
                    "CarLostDetect Service Channel",       // 사용자에게 표시될 채널 이름
                    NotificationManager.IMPORTANCE_DEFAULT // 알림의 중요도
            );

            // 알림의 설명 설정 (선택 사항)
            serviceChannel.setDescription("This channel is used by the CarLostDetect service.");

            // 알림 매니저를 통해 채널 등록
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    /**
     * Foreground Service 시작 메소드
     */
    private void startForeground() {
        // Before starting the service as foreground check that the app has the
        // appropriate runtime permissions. In this case, verify that the user
        // has granted the CAMERA permission.
        int cameraPermission =
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (cameraPermission == PackageManager.PERMISSION_DENIED) {
            // Without camera permissions the service cannot run in the
            // foreground. Consider informing user or updating your app UI if
            // visible.
            stopSelf();
            return;
        }

        try {
            Notification notification =
                    new NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
                            // Create the notification to display while the service
                            // is running
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .build();
            int type = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            ServiceCompat.startForeground(
                    /* service = */ this,
                    /* id = */ FOREGROUND_ID, // Cannot be 0
                    /* notification = */ notification,
                    /* foregroundServiceType = */ type
            );
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e instanceof ForegroundServiceStartNotAllowedException
            ) {
                // App not in a valid state to start foreground service
                // (e.g started from bg)
                Log.e(TAG, Objects.requireNonNull(e.getMessage()));
            }

        }
    }


    /**
     * Service의 주요 기능들을 실행시키는 메소드
     *
     * @param name
     */


    /**
     * 카메라를 선택해 VideoTrack을 만드는 메소드들
     * <p>
     * createMediaStream, createVideoCapturer, createCameraCaputurer
     */
    private void createMediaStream() {
        videoCapturer = createVideoCapturer();
        if (videoCapturer == null) {
            Log.e(TAG, "VideoCapturer 생성 실패");
            return;
        }
        videoSource = peerManager.getPeerConnectionFactory().createVideoSource(videoCapturer.isScreencast());
        localVideoTrack = peerManager.getPeerConnectionFactory().createVideoTrack(LOCAL_VIDEO_TRACK_ID, videoSource);

        localVideoTrack.addSink(videoSink);

        videoCapturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());

        try {
            videoCapturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS);
        } catch (Exception e) {
            Log.e(TAG, "비디오 캡처 시작 중 오류 발생", e);
        }
    }

    private VideoCapturer createVideoCapturer() {
        if (Camera2Enumerator.isSupported(this)) {
            return createCameraCapturer(new Camera2Enumerator(this));
        } else {
            return createCameraCapturer(new Camera1Enumerator(true));
        }
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        String usingDevice = deviceNames[0];
        if (deviceNames.length >= 2) {
            usingDevice = deviceNames[1];
        }

        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName) && deviceName.equals(usingDevice)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName) && deviceName.equals(usingDevice)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        return null;
    }

    private void detectObject(VideoFrame videoFrame) {
        Bitmap bitmap = videoFrameToBitmap(videoFrame);

        try {
            Log.d(TAG, "detectObject: " + videoFrame);
            objectDetectorHelper.detect(bitmap, videoFrame.getRotation());
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private Bitmap videoFrameToBitmap(VideoFrame videoFrame) {
        // I420Buffer 추출 (YUV 포맷의 버퍼)
        VideoFrame.I420Buffer i420Buffer = videoFrame.getBuffer().toI420();

        try {
            // Y, U, V Plane 데이터를 가져옴
            ByteBuffer yPlane = i420Buffer.getDataY();
            ByteBuffer uPlane = i420Buffer.getDataU();
            ByteBuffer vPlane = i420Buffer.getDataV();

            int ySize = yPlane.remaining();
            int uSize = uPlane.remaining();
            int vSize = vPlane.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];

            yPlane.get(nv21, 0, ySize);
            vPlane.get(nv21, ySize, vSize);
            uPlane.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, i420Buffer.getWidth(), i420Buffer.getHeight(), null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, i420Buffer.getWidth(), i420Buffer.getHeight()), 100, out);
            byte[] imageBytes = out.toByteArray();
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        } finally {
            // I420Buffer 해제
            i420Buffer.release();
        }
    }

    public List<Detection> filterDetections(List<Detection> detections, List<String> excludeClasses) {
        // detections가 null일 경우 null을 반환
        if (detections == null) {
            return null;
        }

        // 스트림을 사용하여 필터링 및 변환
        return detections.stream()
                .filter(detection -> {
                    // Detection 객체의 클래스 이름 (label)을 가져옴
                    String detectedClass = detection.getCategories().get(0).getLabel();
                    // 제외 클래스 목록에 없으면 필터링 통과
                    boolean hasExclude = excludeClasses.contains(detectedClass);
                    Log.d(TAG, "filterDetections: " + detectedClass + " hasExclude " + hasExclude);
                    return !hasExclude;
                })
                .collect(Collectors.toCollection(ArrayList::new)); // 결과를 ArrayList로 변환
    }

    public void processResults(List<Detection> results, int imageHeight, int imageWidth) {
        // 식별된 Item 정보들을 임시 저장하는 변수
        ArrayList<String> items = new ArrayList<>();

        // 식별된 Item 정보 수신 측에 전달 & tmp 배열에 저장
        Log.d(TAG, "processResults: " + results);
        for (Detection detection : results) {
            String message = detection.getCategories().get(0).getLabel();
            objectLabels += message + "/";
            items.add(message);
        }

        // CAN 통신이 물리면 사용할 분실물 탐지 로직
        /*
        SampleCanLogic(items);
         */
        // 위 메소드 사용 시 아래 if-else문 삭제

        // 사람이 인식되었다면 사람 승차 확인, 사람이 인식되지 않았다면 beforeList에 현재 상태 저장
        Log.d(TAG, "processResults: items" + items);
        if (items.contains(getResources().getString(R.string.person_label))) {
            if (!personCheck) {
                Log.d(TAG, "------ 사람의 승차를 확인했습니다 ------");
            }
            personCheck = true;
        } else {
            // 이전에 사람이 타고 있었고, 분실물 확인 기능을 활성화 했다면 분실물 확인
            if (personCheck && isActive) {
                Log.d(TAG, "------ 사람의 하차를 확인했습니다. 놓고 내린 물건을 확인하겠습니다 ------");
                afterList = items;

                String lost = findLostItems(beforeList, afterList);

                listener.setLostString(lost);

                if (!lost.isEmpty()) {
                    alertHelper.playTone(lost);
                }
            }

            personCheck = false;
            beforeList = items;
            Log.d(TAG, "------ beforeList 갱신 ------");
        }

        if (detectorStatus == ObjectDetectStatus.NOT_DETECT) {
//            customBoxView.setResults(results, imageHeight, imageWidth);
            listener.drawCustomBox(results, imageHeight, imageWidth);
        }
        detectorStatus = ObjectDetectStatus.DETECT;

        if (detectorStatus == ObjectDetectStatus.DETECT) {
            if (!results.isEmpty()) {
                // 새로운 탐지 결과가 있을 때 결과 Update
                listener.drawCustomBox(results, imageHeight, imageWidth);
                // CustomView 다시 그리기
                listener.invalidateCustomBox();
            }
        }
    }

    /**
     * CAN 통신이 물리면 사용할 로직(임시)
     *
     * @param items
     */
    private void SampleCanLogic(ArrayList<String> items) {
        isHuman(items);

        switch (status) {
            case BEFORE:
                if (door) {
                    status = BoardingStatus.DURING;
                } else {
                    beforeList = items;
                    Log.d(TAG, "------ beforeList 갱신 ------");
                }
                break;

            case DURING:
                if (!door && seat && human) {
                    status = BoardingStatus.AFTER;
                } else {
                    if (!door) {
                        beforeList = items;
                        Log.d(TAG, "------ beforeList 갱신 ------");
                        status = BoardingStatus.BEFORE;
                    } else {
                        status = BoardingStatus.DURING;
                    }
                }
                break;

            case AFTER:
                if (door && !seat && !human) {
                    Log.d(TAG, "------ 사람의 하차를 확인했습니다. 놓고 내린 물건을 확인하겠습니다 ------");
                    afterList = items;

                    String lost = findLostItems(beforeList, afterList);

                    listener.setLostString(lost);

                    if (!lost.isEmpty() && isActive) {
                        alertHelper.playTone(lost);
                    }
                    status = BoardingStatus.DURING;
                } else {
                    status = BoardingStatus.AFTER;
                }
                break;

            default:
                break;
        }
    }

    /**
     * 사람이 인식되었는지 확인해 차량 안에 사람이 있는지 확인하는 함수
     *
     * @param items
     */
    private void isHuman(ArrayList<String> items) {
        if (items.contains(getResources().getString(R.string.person_label))) {
            human = true;
        } else {
            human = false;
        }
    }

    /**
     * 차 문이 열렸는지 확인해 저장하는 함수 (미완성)
     */
    private void isDoor() {
        // 문의 상태를 저장한 JSON 데이터를 받아 door 값을 바꿔주는 함수
    }

    /**
     * 시트에 사람이 앉아있는지 확인해 저장하는 함수 (미완성)
     */
    private void isSeat() {
        // 시트의 상태를 저장한 JSON 데이터를 받아 seat 값을 바꿔주는 함수
    }

    /**
     * beforeList와 afterList를 비교해 두고 내린 물건을 찾아 알려주는 함수
     */
    private String findLostItems(ArrayList<String> beforeList, ArrayList<String> afterList) {
        // afterList 에서 beforeList 에 있는 값들을 제거
        for (String item : beforeList) {
            afterList.remove(item);
        }

        StringBuilder lostItems = new StringBuilder();

        // afterList 에 남아있는 물건들이 분실물
        if (!afterList.isEmpty()) {
            for (String item : afterList) {
                lostItems.append(item).append(" ");
            }
            Log.d(TAG, "두고 간 물건 : " + lostItems.toString());
        }

        return lostItems.toString();
    }

    public class DoorReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int doorSignal = intent.getIntExtra("msg_type", 0);
            String txt = intent.getStringExtra("msg_data");

            Log.d("Receiver", "ReceivedData : " + doorSignal + " :  " + txt);
            // 카메라 상태 갱신
//            settingCamera();
        }
    }

    public class SeatReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
//            seatSignal = intent.getBooleanExtra("test", false);
//            Log.d("Receiver", "ReceivedData : " + seatSignal);
//             카메라 상태 갱신
//            settingCamera();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void enrollReceiver() {
        doorBroadcastReceiver = new DoorReceiver();
        seatBroadcastReceiver = new SeatReceiver();
        IntentFilter doorIntentFilter = new IntentFilter("TEST1");
        IntentFilter seatIntentFilter = new IntentFilter("TEST2");

        registerReceiver(doorBroadcastReceiver, doorIntentFilter, Context.RECEIVER_EXPORTED);
        registerReceiver(seatBroadcastReceiver, seatIntentFilter, Context.RECEIVER_EXPORTED);
    }

    /**
     * 추가된 메서드
     */
    /*private void settingCamera() {
        if (signal) {
            if (videoCapturer == null) {
                videoCapturer = createVideoCapturer();
                if (videoCapturer == null) {
                    Log.e(TAG, "VideoCapturer 생성 실패");
                    return;
                }
                videoSource = peerManager.getPeerConnectionFactory().createVideoSource(videoCapturer.isScreencast());
                localVideoTrack = peerManager.getPeerConnectionFactory().createVideoTrack(LOCAL_VIDEO_TRACK_ID, videoSource);

                localVideoTrack.addSink(videoSink);

                videoCapturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
            }

            try {
                videoCapturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS);
                Log.d(TAG, "Before List" + beforeList.toString());
            } catch (Exception e) {
                Log.e(TAG, "비디오 캡처 시작 중 오류 발생", e);
            }

            if (listener != null) {
                listener.onVideoTrackReceived(localVideoTrack);
            }
        } else {
            if (videoCapturer != null) {
                try {
                    videoCapturer.stopCapture();
                } catch (InterruptedException e) {
                    Log.e(TAG, "비디오 캡처 중지 중 오류 발생", e);
                }
                videoCapturer.dispose();
                videoCapturer = null;
                Log.d(TAG, "VideoCapturer 리소스 해제됨");
            }

            if (videoSource != null) {
                videoSource.dispose();
                videoSource = null;
                Log.d(TAG, "VideoSource 리소스 해제됨");
            }

            if (localVideoTrack != null) {
                localVideoTrack.setEnabled(false);
                localVideoTrack.removeSink(videoSink);
                localVideoTrack = null;
                Log.d(TAG, "LocalVideoTrack 리소스 해제됨");
            }
        }
    }*/


    /**
     * OnMqttCallback 인터페이스 구현 클래스
     */
    private class AWSCallback implements OnMqttCallback {
        private final String myName;

        AWSCallback(String myName) {
            this.myName = myName;
        }

        @Override
        public void connected() {
            String topic = "webrtc/" + myName + "/#";
            awsIotMqtt.subscribe(topic);
        }

        @Override
        public void onSubscribeSuccess(String topic) {
        }

        @Override
        public void messageArrived(String topic, byte[] data) {
            try {
                String payload = new String(data, "UTF-8");
                Log.d(TAG, "메시지 수신됨 - 토픽: " + topic + ", 메시지: " + payload);

                String[] topics = topic.split("/");
                int topicsLen = topics.length;

                if (topic.equals("webrtc/" + myName + "/signal")) {
                    peerManager.createPeerConnection(payload);
                    peerManager.addTrack(payload, localVideoTrack);
                    peerManager.createDataChannel(payload);
                    peerManager.createOffer(payload);
                } else if (topicsLen == 4) {
                    String peerId = topics[2];
                    String messageType = topics[3];

                    switch (messageType) {
                        case "offer":
                            // Offer 수신 시 처리
                            peerManager.setRemoteDescription(peerId, payload, SessionDescription.Type.OFFER);
                            peerManager.createAnswer(peerId);
                            break;
                        case "answer":
                            // Answer 수신 시 처리
                            peerManager.setRemoteDescription(peerId, payload, SessionDescription.Type.ANSWER);
                            break;
                        case "ice":
                            // ICE 후보 수신 시 처리
                            peerManager.addRemoteCandidate(peerId, payload);
                            break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "메시지 수신 중 오류 발생 : ", e);
            }
        }
    }

    private void startVideoCapture() {

    }

    private void stopVideoCapture() {

    }

    /**
     * OnPeerCallback 인터페이스 구현 클래스
     */
    private class PeerCallback implements OnPeerCallback {

        private final String myName;

        PeerCallback(String myName) {
            this.myName = myName;
        }

        @Override
        public void onIceCandidate(String peerName, IceCandidate iceCandidate) {
            String topic = "webrtc/" + peerName + "/" + myName + "/ice";
            awsIotMqtt.publish(topic, iceCandidate.sdp);
        }

        @Override
        public void onOfferCreated(String peerName, SessionDescription sessionDescription) {
            String topic = "webrtc/" + peerName + "/" + myName + "/offer";
            String offer_SDP = sessionDescription.description;
            awsIotMqtt.publish(topic, offer_SDP);
        }

        @Override
        public void onAnswerCreated(String peerName, SessionDescription sessionDescription) {
            // 영상 수신 측 구현 부분
        }

        @Override
        public void onMessage(String receivedMessage) {

        }

        @Override
        public void onMessage(String receivedMessage, String peerName) {
            // 영상 수신 측 구현 부분
        }

        @Override
        public void onTrack(RtpTransceiver transceiver, String peerName) {
            // 영상 수신 측 구현 부분
        }
    }

    /**
     * DetectorListener 인터페이스 구현 클래스
     */
    private class objectDetectorListener implements DetectorListener {

        @Override
        public void onError(@NonNull String error) {
            Log.d(TAG, "Error Occurred");
        }

        @Override
        public void onResults(@Nullable List<Detection> results, long inferenceTime, int imageHeight, int imageWidth) {
            if (!isProcessing) {
                isProcessing = true;
                executorService.submit(() -> {
                    try {
                        Log.d(TAG, "onResults:" +results + "imageHeight: " + imageHeight + "imageWidth: " + imageWidth);
                        // 리스트 필터링
                        List<Detection> filteredResults = filterDetections(results, excludeLabels);

                        // 필터링 된 리스트 전달

                        processResults(
                               results,
                                imageHeight,
                                imageWidth
                        );

                        // 데이터 보내기
                        if (objectLabels.isEmpty()) {
                            objectLabels = getResources().getString(R.string.no_label);
                        }
                        // sendDataToService(objectLabels);
                        if (peerManager != null) {
                            peerManager.sendData(objectLabels);
                        }

                        objectLabels = "";

                        // 딜레이 처리
                        Thread.sleep(DETECT_INTERVAL);
                    } catch (InterruptedException e) {
                        // 인터럽트 예외 처리: 현재 스레드를 다시 인터럽트 상태로 설정
                        Thread.currentThread().interrupt();
                        Log.e(TAG, "Thread interrupted during sleep", e);
                    } catch (Exception e) {
                        Log.e(TAG, "오류 발생", e);
                    } finally {
                        isProcessing = false;
                    }
                });
            }
        }

    }
}

