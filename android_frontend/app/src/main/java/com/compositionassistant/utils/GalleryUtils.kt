package com.compositionassistant.utils

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

object GalleryUtils {

    fun canOpenGallery(context: Context): Boolean {
        return buildGalleryIntents().any { intent ->
            intent.resolveActivity(context.packageManager) != null
        }
    }

    fun openGallery(context: Context): Boolean {
        val intent = buildGalleryIntents().firstOrNull { candidate ->
            candidate.resolveActivity(context.packageManager) != null
        } ?: return false

        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun loadLatestThumbnail(
        context: Context,
        targetSizePx: Int = 220
    ): Bitmap? {
        val latestUri = queryLatestPhotoUri(context) ?: return null
        return loadThumbnail(context, latestUri, targetSizePx)
    }

    fun loadThumbnail(
        context: Context,
        uri: Uri,
        targetSizePx: Int = 220
    ): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(
                    uri,
                    Size(targetSizePx, targetSizePx),
                    null
                )
            } else {
                context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }
        }.getOrNull()
    }

    private fun buildGalleryIntents(): List<Intent> {
        return listOf(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_GALLERY)
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(
                Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ).apply {
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    private fun queryLatestPhotoUri(context: Context): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.IS_PENDING} = 0"
        } else {
            null
        }

        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, " +
                    "${MediaStore.Images.Media.DATE_ADDED} DESC, " +
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )
        }.getOrNull() ?: return null

        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }
            val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }
    }
}
