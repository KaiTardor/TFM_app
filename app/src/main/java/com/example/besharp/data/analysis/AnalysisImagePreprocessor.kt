package com.example.besharp.data.analysis

// Prepara y reutiliza un JPEG reducido y sin EXIF para el analisis visual.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import androidx.core.graphics.scale

class ImagePreparationException(
    val stage: String,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

object AnalysisImagePreprocessor {
    private const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 90
    private const val MAX_SOURCE_BYTES = 50L * 1024 * 1024
    private const val CACHE_DIRECTORY = AnalysisTemporaryFiles.NORMALIZED_DIRECTORY
    private val cacheLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun prepareCached(context: Context, assessmentId: Long, uri: Uri): AnalysisInput = withContext(Dispatchers.IO) {
        AnalysisTemporaryFiles.ensureCleaned(context)
        require(assessmentId > 0) { "La evaluacion no es valida" }
        val lock = cacheLocks.getOrPut(assessmentId) { Mutex() }
        lock.withLock {
            val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
            val cachedFile = File(cacheDirectory, "$assessmentId.jpg")
            if (cachedFile.isFile && cachedFile.length() > 0) {
                return@withLock AnalysisInput(cachedFile.readBytes(), "image/jpeg")
            }

            val prepared = prepare(context, uri)
            val temporary = try {
                File.createTempFile("assessment-$assessmentId-", ".tmp", cacheDirectory)
            } catch (exception: Exception) {
                throw ImagePreparationException(
                    stage = "cache",
                    message = "No se pudo crear la copia visual temporal",
                    cause = exception
                )
            }
            try {
                temporary.writeBytes(prepared.imageBytes)
                if (!temporary.renameTo(cachedFile)) {
                    cachedFile.writeBytes(prepared.imageBytes)
                }
            } finally {
                temporary.delete()
            }
            prepared
        }
    }

    suspend fun clearCached(context: Context, assessmentId: Long) = withContext(Dispatchers.IO) {
        val lock = cacheLocks.getOrPut(assessmentId) { Mutex() }
        try {
            lock.withLock {
                val file = File(File(context.cacheDir, CACHE_DIRECTORY), "$assessmentId.jpg")
                if (!file.delete() && file.exists()) throw IOException("No se pudo eliminar la copia normalizada")
            }
        } finally {
            cacheLocks.remove(assessmentId, lock)
        }
    }

    suspend fun prepare(context: Context, uri: Uri): AnalysisInput = withContext(Dispatchers.IO) {
        AnalysisTemporaryFiles.ensureCleaned(context)
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri).orEmpty()
        var declaredLength = -1L
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            declaredLength = descriptor.length
            requireImage(
                descriptor.length <= 0 || descriptor.length <= MAX_SOURCE_BYTES,
                stage = "source-size",
                message = "La imagen supera el limite de 50 MiB"
            )
        }
        Log.d(TAG, "prepare start mime=$mimeType declaredBytes=$declaredLength scheme=${uri.scheme}")

        val preprocessingDirectory = File(
            context.cacheDir,
            AnalysisTemporaryFiles.PREPROCESSING_DIRECTORY
        ).apply { mkdirs() }
        val sourceFile = File.createTempFile("analysis-source-", ".image", preprocessingDirectory)
        try {
            // Copiar la imagen desde el URI a un archivo temporal para su procesamiento
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(sourceFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        requireImage(
                            total <= MAX_SOURCE_BYTES,
                            stage = "copy-size",
                            message = "La imagen supera el limite de 50 MiB"
                        )
                        output.write(buffer, 0, count)
                    }
                    requireImage(total > 0, "copy", "La imagen esta vacia")
                }
            } ?: throw ImagePreparationException("open", "No se pudo abrir la imagen")

            // Obtener las dimensiones de la imagen sin cargarla completamente
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            val decodedImage = decodeImage(sourceFile, bounds)
            val decoded = decodedImage.bitmap

            try {
                val orientation = if (decodedImage.orientationApplied) {
                    ExifInterface.ORIENTATION_NORMAL
                } else {
                    ExifInterface(sourceFile).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
                val oriented = applyExifOrientation(decoded, orientation)
                try {
                    // Ajustar el tamaño de la imagen orientada
                    val (width, height) = targetSize(oriented.width, oriented.height)
                    val prepared = if (width != oriented.width || height != oriented.height) {
                        oriented.scale(width, height)
                    } else {
                        oriented
                    }
                    try {
                        val bytes = ByteArrayOutputStream().use { output ->
                            requireImage(
                                prepared.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output),
                                stage = "encode",
                                message = "No se pudo preparar la imagen"
                            )
                            output.toByteArray()
                        }
                        Log.d(
                            TAG,
                            "prepare success source=${bounds.outWidth}x${bounds.outHeight} " +
                                "output=${prepared.width}x${prepared.height} bytes=${bytes.size}"
                        )
                        AnalysisInput(bytes, "image/jpeg")
                    } finally {
                        if (prepared !== oriented) prepared.recycle()
                    }
                } finally {
                    if (oriented !== decoded) oriented.recycle()
                }
            } finally {
                decoded.recycle()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ImagePreparationException) {
            Log.e(TAG, "prepare failed stage=${exception.stage} mime=$mimeType", exception)
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "prepare failed stage=transform mime=$mimeType", exception)
            throw ImagePreparationException(
                stage = "transform",
                message = "No se pudo transformar la imagen",
                cause = exception
            )
        } finally {
            sourceFile.delete()
        }
    }

    private fun decodeImage(
        sourceFile: File,
        bounds: BitmapFactory.Options
    ): DecodedImage {
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_DIMENSION) {
                sampleSize *= 2
            }
            BitmapFactory.decodeFile(
                sourceFile.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )?.let { return DecodedImage(it, orientationApplied = false) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(sourceFile)) {
                        decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val (width, height) = targetSize(info.size.width, info.size.height)
                    if (width != info.size.width || height != info.size.height) {
                        decoder.setTargetSize(width, height)
                    }
                }
                Log.d(TAG, "BitmapFactory fallback succeeded with ImageDecoder")
                return DecodedImage(bitmap, orientationApplied = true)
            } catch (exception: Exception) {
                Log.e(TAG, "ImageDecoder fallback failed", exception)
                throw ImagePreparationException(
                    stage = "decode",
                    message = "Formato de imagen no compatible",
                    cause = exception
                )
            }
        }
        throw ImagePreparationException("decode", "Formato de imagen no compatible")
    }

    internal fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Dimensiones de imagen no validas" }
        val ratio = minOf(MAX_DIMENSION.toFloat() / width, MAX_DIMENSION.toFloat() / height, 1f)
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }

    private fun requireImage(condition: Boolean, stage: String, message: String) {
        if (!condition) throw ImagePreparationException(stage, message)
    }

    private data class DecodedImage(
        val bitmap: Bitmap,
        val orientationApplied: Boolean
    )

    // Aplica la orientación EXIF a un bitmap dado, devolviendo un nuevo bitmap con la orientación correcta.
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private const val TAG = "BeSharpImage"
}
