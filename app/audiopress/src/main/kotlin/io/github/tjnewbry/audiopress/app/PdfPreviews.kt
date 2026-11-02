package io.github.tjnewbry.audiopress.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Low-resolution page previews of the PDF in [file], drawn with Android's [PdfRenderer] only when a page is asked for, and
 * kept in a small cache ([cacheBytes]) so a book of hundreds of pages never holds more than a few bitmaps. If the PDF cannot be
 * opened by the renderer (damaged, or protected), [usable] is false and every page is simply missing (null).
 *
 * The renderer allows one open page at a time, so renders take turns. [close] frees the renderer and the cache.
 */
class PdfPreviews(
    file: File,
    private val cacheBytes: Int = DEFAULT_CACHE_BYTES,
) : Closeable {
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val lock = Any()
    private val cache =
        object : LruCache<Int, Bitmap>(cacheBytes) {
            override fun sizeOf(
                key: Int,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    /** Height divided by width of the first page, used for every page so the list has one fixed row height. */
    val aspect: Float

    val usable: Boolean get() = renderer != null

    init {
        var ratio = DEFAULT_ASPECT
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor = fd
            val opened = PdfRenderer(fd) // throws SecurityException for a password, IOException for a damaged file
            renderer = opened
            if (opened.pageCount > 0) {
                opened.openPage(0).use { page ->
                    if (page.width >
                        0
                    ) {
                        ratio = (page.height.toFloat() / page.width).coerceIn(MIN_ASPECT, MAX_ASPECT)
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "The page previews could not be opened", e)
            closeQuietly()
        } catch (e: SecurityException) {
            Log.w(TAG, "The page previews could not be opened", e)
            closeQuietly()
        } catch (e: RuntimeException) {
            Log.w(TAG, "The page previews could not be opened", e)
            closeQuietly()
        }
        aspect = ratio
    }

    /** The preview of page [pageNumber] (1-based) about [widthPx] wide, or null when it cannot be drawn. */
    suspend fun page(
        pageNumber: Int,
        widthPx: Int,
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                ensureActive() // a page that scrolled away while waiting its turn is not drawn
                cache.get(pageNumber) ?: render(pageNumber, widthPx)?.also { cache.put(pageNumber, it) }
            }
        }

    private fun render(
        pageNumber: Int,
        widthPx: Int,
    ): Bitmap? {
        val active = renderer ?: return null
        if (pageNumber !in 1..active.pageCount || widthPx <= 0) return null
        return try {
            active.openPage(pageNumber - 1).use { page ->
                val height = (widthPx * aspect).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                // Fit the page inside the bitmap, whatever its own proportions.
                val scale = minOf(widthPx.toFloat() / page.width, height.toFloat() / page.height)
                val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Page $pageNumber could not be drawn", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Page $pageNumber could not be drawn: out of memory", e)
            cache.evictAll()
            null
        }
    }

    private fun closeQuietly() {
        try {
            renderer?.close()
            descriptor?.close()
        } catch (e: IOException) {
            // Nothing more to do.
        }
        renderer = null
        descriptor = null
    }

    override fun close() {
        synchronized(lock) {
            closeQuietly()
            cache.evictAll()
        }
    }

    private companion object {
        const val TAG = "AudioPressPreview"
        const val DEFAULT_CACHE_BYTES = 12 * 1024 * 1024
        const val DEFAULT_ASPECT = 1.414f
        const val MIN_ASPECT = 0.5f
        const val MAX_ASPECT = 2.5f
    }
}
