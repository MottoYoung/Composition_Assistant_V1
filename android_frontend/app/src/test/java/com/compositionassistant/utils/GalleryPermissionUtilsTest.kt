package com.compositionassistant.utils

import android.Manifest
import android.os.Build
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GalleryPermissionUtilsTest {

    @Test
    fun android14AndAboveRequestsImagesAndSelectedVisualAccess() {
        assertArrayEquals(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ),
            GalleryPermissionUtils.permissionsToRequestForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
    }

    @Test
    fun android13RequestsImageMediaAccess() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
            GalleryPermissionUtils.permissionsToRequestForSdk(Build.VERSION_CODES.TIRAMISU)
        )
    }

    @Test
    fun preAndroid13RequestsExternalStorageReadAccess() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            GalleryPermissionUtils.permissionsToRequestForSdk(Build.VERSION_CODES.S_V2)
        )
    }
}
