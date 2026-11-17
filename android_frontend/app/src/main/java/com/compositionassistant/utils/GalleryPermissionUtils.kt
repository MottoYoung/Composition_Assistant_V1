package com.compositionassistant.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object GalleryPermissionUtils {

    fun permissionsToRequest(): Array<String> {
        return permissionsToRequestForSdk(Build.VERSION.SDK_INT)
    }

    fun permissionsToRequestForSdk(sdkInt: Int): Array<String> {
        return when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun hasGalleryReadPermission(context: Context): Boolean {
        fun hasPermission(permission: String): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
                    hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                hasPermission(Manifest.permission.READ_MEDIA_IMAGES)
            }
            else -> hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
