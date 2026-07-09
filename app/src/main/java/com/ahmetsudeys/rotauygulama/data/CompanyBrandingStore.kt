package com.ahmetsudeys.rotauygulama.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object CompanyBrandingStore {
    private const val COMPANY_LOGO_FILE_NAME = "company_logo.png"

    /** Longest edge (px) we downsample a picked image to before showing it in the cropper. */
    private const val MAX_SOURCE_DIMEN = 1600

    /**
     * Decodes a picked image into a memory-friendly bitmap, honouring EXIF rotation so
     * photos taken in portrait aren't shown sideways in the cropper.
     */
    fun loadBitmapForCrop(context: Context, source: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_SOURCE_DIMEN) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = context.contentResolver.openInputStream(source)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        applyExifOrientation(decoded, orientation)
    }.getOrNull()

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /**
     * Persists an already-cropped (circular) logo bitmap into app-internal storage as PNG,
     * preserving transparency. Returns the stored [File] on success, or null on failure.
     */
    fun saveCompanyLogoBitmap(context: Context, bitmap: Bitmap): File? {
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        return saveCompanyLogoFromBytes(context, bytes)
    }

    /**
     * Copies the selected logo image into app-internal storage and persists its absolute path in [Prefs].
     * Returns the stored [File] on success, or null on failure.
     */
    fun saveCompanyLogoFromUri(context: Context, source: Uri): File? {
        val outFile = File(context.filesDir, COMPANY_LOGO_FILE_NAME)
        return runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            } ?: return null

            Prefs.setCompanyLogoPath(context, outFile.absolutePath)
            outFile
        }.getOrNull()?.takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * Restores the company logo from raw bytes (used by backup restore) into app-internal storage
     * and persists its path in [Prefs]. Returns the stored [File] on success, or null on failure.
     */
    fun saveCompanyLogoFromBytes(context: Context, bytes: ByteArray): File? {
        if (bytes.isEmpty()) return null
        val outFile = File(context.filesDir, COMPANY_LOGO_FILE_NAME)
        return runCatching {
            FileOutputStream(outFile).use { output ->
                output.write(bytes)
                output.flush()
            }
            Prefs.setCompanyLogoPath(context, outFile.absolutePath)
            outFile
        }.getOrNull()?.takeIf { it.exists() && it.length() > 0 }
    }
}


