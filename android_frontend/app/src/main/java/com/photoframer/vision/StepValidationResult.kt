package com.photoframer.vision

/**
 * 单帧引导步骤的验证结果。
 */
data class StepValidationResult(
    val isCompleted: Boolean,
    val progress: Float,
    val feedbackText: String,
    val shiftDistance: Float?,
    val tx: Float?,
    val ty: Float?,
    val scaleFactor: Float?,
    val rotationAngle: Float?,
    val matchQuality: Float,
    val uiHintX: Float? = null,
    val uiHintY: Float? = null,
    val directionConfidence: Float? = null,
    val subjectConfidence: Float? = null,
    val hasSubject: Boolean? = null,
    val uiSpaceWidth: Float? = null,
    val uiSpaceHeight: Float? = null
)
