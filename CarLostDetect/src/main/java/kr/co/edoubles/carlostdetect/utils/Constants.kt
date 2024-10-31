package kr.co.edoubles.carlostdetect.utils

// 카메라 권한 코드
const val PERMISSION_CODE = 9999

const val DETECT_INTERVAL = 500L

const val TEXT_PADDING = 16

// Serivce에서 쓰이는 상수들
const val FOREGROUND_CHANNEL_ID="CarLostDetect_CHANNEL_ID"
const val FOREGROUND_ID=100

const val LOCAL_VIDEO_TRACK_ID="localVideoTrack"
const val CAPTURE_WIDTH=640
const val CAPTURE_HEIGHT=480
const val CAPTURE_FPS=30

enum class BoardingStatus {
    BEFORE, DURING, AFTER
}