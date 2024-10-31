package kr.co.edoubles.carlostdetect.detect

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.vision.detector.ObjectDetector

class ObjectDetectorHelper(
    private var threshold: Float = 0.5f,
    private var maxResults: Int = 5,
    private val context: Context,
    private val objectDetectorListener: DetectorListener?
) {

    private var objectDetector: ObjectDetector? = null

    /*init {
        setupObjectDetector()
    }*/

    fun clearObjectDetector() {
        objectDetector?.close()
        objectDetector = null
    }

    private fun setupObjectDetector() {
        val optionsBuilder =
            ObjectDetector.ObjectDetectorOptions.builder()
                .setScoreThreshold(threshold)
                .setMaxResults(maxResults)

        val modelName = "model.tflite"

        try {
            objectDetector =
                ObjectDetector.createFromFileAndOptions(context, modelName, optionsBuilder.build())
        } catch (e: IllegalStateException) {
            objectDetectorListener?.onError(
                "Object detector failed to initialize. See error logs for details"
            )
            Log.e(TAG, "TFLite failed to load model with error: " + e.message)
        }
    }

    /**
     * Detect
     * TensorFlow Lite를 활용하여 물체가 탐지 되는 Core 부분
     * @param image : 탐지를 위한 이미지
     */
    fun detect(image: Bitmap, imageRotation: Int) {
        if (objectDetector == null) {
            setupObjectDetector()
        }
        var inferenceTime = SystemClock.uptimeMillis()

        // 이미지 각도 조정
        val imageProcessor =
            ImageProcessor.Builder()
                .add(Rot90Op(-imageRotation / 90))
                .build()

        // Bitmap으로 변환 후 ImageProcessor로 처리
        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(image))

        // 물체 탐지
        val results = objectDetector?.detect(tensorImage)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime
        Log.d(TAG, "detect: $results" )
        // Callback\
        objectDetectorListener?.onResults(
            results,
            inferenceTime,
            tensorImage.height,
            tensorImage.width
        )
    }

    companion object {
        private const val TAG = "ObjectDetectorHelper"
    }
}