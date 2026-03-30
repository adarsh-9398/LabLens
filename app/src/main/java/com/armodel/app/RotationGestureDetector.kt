package com.armodel.app

import android.view.MotionEvent
import kotlin.math.atan2

/**
 * A gesture detector that tracks the rotation angle between two fingers.
 * This is used specifically to handle Z-axis twists (like turning a knob).
 */
class RotationGestureDetector(private val listener: OnRotationGestureListener) {

    interface OnRotationGestureListener {
        fun onRotation(rotationDetector: RotationGestureDetector): Boolean
    }

    private var focalX = 0f
    private var focalY = 0f
    private var initialAngle = 0f
    private var currentAngle = 0f
    private var angleDelta = 0f
    private var isInProgress = false

    fun getAngleDelta(): Float {
        return angleDelta
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isInProgress = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    initialAngle = angleBetweenLines(event)
                    currentAngle = initialAngle
                    isInProgress = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isInProgress && event.pointerCount >= 2) {
                    val newAngle = angleBetweenLines(event)
                    angleDelta = newAngle - currentAngle
                    
                    // Normalize angle delta to avoid jumps from +180 to -180
                    if (angleDelta > 180f) {
                        angleDelta -= 360f
                    } else if (angleDelta < -180f) {
                        angleDelta += 360f
                    }

                    if (angleDelta != 0f) {
                        currentAngle = newAngle
                        listener.onRotation(this)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    // One finger lifted, so twist ends
                    isInProgress = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isInProgress = false
            }
        }
        return true
    }

    private fun angleBetweenLines(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return angle
    }
}
