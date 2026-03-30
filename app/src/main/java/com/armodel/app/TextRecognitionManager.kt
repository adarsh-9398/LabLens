package com.armodel.app

import android.media.Image
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Manages ML Kit text recognition from camera frames.
 * Includes throttling to avoid processing every single frame.
 *
 * IMPORTANT: This class takes ownership of the Image passed to processFrame()
 * and will close it after ML Kit finishes processing.
 */
class TextRecognitionManager {

    companion object {
        private const val TAG = "TextRecognitionMgr"
    }

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var lastProcessedTime: Long = 0
    private val processingIntervalMs: Long = 800 // Process a frame every 800ms (reduces lag)
    @Volatile
    private var isProcessing: Boolean = false

    /**
     * Callback interface for text recognition results.
     */
    interface OnTextDetectedListener {
        fun onTextDetected(detectedWords: List<String>)
        fun onError(error: Exception)
    }

    /**
     * Process camera frame for text recognition.
     * Automatically throttles to avoid overwhelming the device.
     *
     * IMPORTANT: This method takes ownership of the image and will close it
     * after processing completes. The caller must NOT close the image.
     *
     * @param image The camera frame as android.media.Image (will be closed by this method)
     * @param rotationDegrees The rotation to apply to the image
     * @param listener Callback for results
     * @return true if the frame was accepted for processing, false if it was skipped (throttled)
     */
    fun processFrame(image: Image, rotationDegrees: Int, listener: OnTextDetectedListener): Boolean {
        val currentTime = System.currentTimeMillis()
        if (isProcessing || (currentTime - lastProcessedTime) < processingIntervalMs) {
            // Throttled — caller must close the image since we're not taking it
            return false
        }

        isProcessing = true
        lastProcessedTime = currentTime

        try {
            val inputImage = InputImage.fromMediaImage(image, rotationDegrees)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    // Close the image NOW that ML Kit is done with it
                    image.close()
                    isProcessing = false

                    val words = mutableListOf<String>()

                    for (block in visionText.textBlocks) {
                        // Add the full block text (all lines combined, no spaces)
                        val blockText = block.text.replace("\\s+".toRegex(), "").trim()
                        if (blockText.length >= 2) {
                            words.add(blockText)
                        }

                        for (line in block.lines) {
                            // Add the full line text (with spaces removed) as a candidate
                            val lineText = line.text.replace("\\s+".toRegex(), "").trim()
                            if (lineText.length >= 2 && lineText != blockText) {
                                words.add(lineText)
                            }
                            // Also add the line text as-is
                            val lineRaw = line.text.trim()
                            if (lineRaw.length >= 2 && lineRaw != lineText) {
                                words.add(lineRaw)
                            }

                            for (element in line.elements) {
                                val word = element.text.trim()
                                if (word.length >= 2) { // Filter out single chars for regular words
                                    words.add(word)
                                }
                            }
                        }
                    }

                    // Deduplicate while preserving order
                    val uniqueWords = words.distinct()

                    Log.d(TAG, "Detected ${uniqueWords.size} candidates: $uniqueWords")

                    if (uniqueWords.isNotEmpty()) {
                        listener.onTextDetected(uniqueWords)
                    }
                }
                .addOnFailureListener { e ->
                    // Close the image even on failure
                    image.close()
                    isProcessing = false
                    Log.e(TAG, "ML Kit processing failed", e)
                    listener.onError(e)
                }

            return true
        } catch (e: Exception) {
            image.close()
            isProcessing = false
            Log.e(TAG, "Error creating InputImage", e)
            listener.onError(e)
            return false
        }
    }

    /**
     * Release resources.
     */
    fun close() {
        recognizer.close()
    }
}
