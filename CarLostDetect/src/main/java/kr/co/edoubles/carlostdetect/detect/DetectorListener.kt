package kr.co.edoubles.carlostdetect.detect

import org.tensorflow.lite.task.vision.detector.Detection

/**
 * Detector listener
 * Object 탐지 Logic 처리를 위한 Listener
 */
interface DetectorListener {
    fun onError(error: String)

    fun onResults(
        results: MutableList<Detection>?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    )
}