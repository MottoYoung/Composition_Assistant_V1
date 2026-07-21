package com.photoframer.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.photoframer.ui.components.AspectRatioOption
import com.photoframer.utils.ImageFileDecoder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService

private const val TAG = "CameraCapture"

internal fun captureAndAnalyze(
    context: Context,
    imageCapture: ImageCapture?,
    executor: ExecutorService,
    onImageCaptured: (File) -> Unit
) {
    if (imageCapture == null) {
        Log.e(TAG, "ImageCapture 未初始化")
        return
    }

    val photoFile = File(
        context.cacheDir,
        "capture_${System.currentTimeMillis()}.jpg"
    )

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.d(TAG, "图片保存成功: ${photoFile.absolutePath}")
                onImageCaptured(photoFile)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "拍照失败", exception)
            }
        }
    )
}

internal fun applyAspectRatioToCapturedFile(
    context: Context,
    sourceFile: File,
    ratioOption: AspectRatioOption
): File {
    val targetRatio = ratioOption.toTargetAspectRatio() ?: return sourceFile

    val bitmap = ImageFileDecoder.decodeBitmapRespectingExif(sourceFile) ?: return sourceFile
    val croppedBitmap = try {
        com.photoframer.vision.ImageConverter.centerCropToAspectRatio(bitmap, targetRatio)
    } catch (_: Exception) {
        return sourceFile
    }

    val outFile = File(context.cacheDir, "capture_ratio_${System.currentTimeMillis()}.jpg")
    return try {
        FileOutputStream(outFile).use { output ->
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
        }
        outFile
    } catch (_: Exception) {
        sourceFile
    } finally {
        if (croppedBitmap !== bitmap) {
            bitmap.recycle()
            croppedBitmap.recycle()
        }
    }
}

internal fun AspectRatioOption.toTargetAspectRatio(): Float? {
    return when (this) {
        AspectRatioOption.RATIO_4_3 -> 4f / 3f
        AspectRatioOption.RATIO_1_1 -> 1f
        AspectRatioOption.FULL -> null
    }
}
