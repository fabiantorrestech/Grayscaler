package io.github.cloudburst.grayscaler

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteOrder

/**
 * Reads back only a vertical strip of a hardware (GPU) screenshot. `Bitmap.copy` and
 * `Bitmap.createBitmap` on a hardware bitmap both read the whole screen back first (~10 MB at
 * 1080×2400); here the GPU draws the strip into a small [ImageReader] and only that comes back.
 * The strip keeps the screenshot's full height so y stays unchanged; only x is offset.
 *
 * The reader and renderer are reused while the strip width (rounded up) stays the same.
 * Experimental: Developer settings → "Crop screenshot on the GPU".
 */
@RequiresApi(Build.VERSION_CODES.Q)
class ScreenshotCropper {

    /** A software bitmap of the strip and where its left edge sits in the screenshot. */
    class Strip(val bitmap: Bitmap, val originX: Int)

    private var reader: ImageReader? = null
    private var renderer: HardwareRenderer? = null
    private val node = RenderNode("GrayscalerScreenshotCrop")

    /**
     * @param left first screenshot column needed (inclusive)
     * @param right last column needed (exclusive)
     * @return the strip, or null if the GPU path failed (callers fall back to a full copy)
     */
    fun crop(screenshot: Bitmap, left: Int, right: Int): Strip? {
        val height = screenshot.height
        val originX = left.coerceIn(0, screenshot.width - 1)
        val width = roundUp(right.coerceAtMost(screenshot.width) - originX, WIDTH_STEP_PX)
            .coerceAtMost(screenshot.width - originX)
        if (width <= 0 || height <= 0) return null
        return try {
            val imageReader = readerFor(width, height)
            val hardwareRenderer = renderer ?: return null
            val canvas = node.beginRecording(width, height)
            try {
                canvas.drawBitmap(screenshot, -originX.toFloat(), 0f, null)
            } finally {
                node.endRecording()
            }
            hardwareRenderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = imageReader.acquireLatestImage() ?: return null
            image.use { Strip(toBitmap(it.planes[0], width, height), originX) }
        } catch (_: Throwable) {
            close()
            null
        }
    }

    fun close() {
        renderer?.destroy()
        renderer = null
        reader?.close()
        reader = null
    }

    private fun readerFor(width: Int, height: Int): ImageReader {
        reader?.let { if (it.width == width && it.height == height) return it }
        close()
        val imageReader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, MAX_IMAGES,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        node.setPosition(0, 0, width, height)
        renderer = HardwareRenderer().apply {
            setSurface(imageReader.surface)
            setContentRoot(node)
        }
        reader = imageReader
        return imageReader
    }

    /** RGBA bytes (with row padding) → an ARGB_8888 bitmap. */
    private fun toBitmap(plane: android.media.Image.Plane, width: Int, height: Int): Bitmap {
        val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                // Little-endian int of bytes R,G,B,A is 0xAABBGGRR; Color wants 0xAARRGGBB.
                val abgr = buffer.getInt(rowStart + x * 4)
                pixels[y * width + x] = (abgr and 0xFF00FF00.toInt()) or
                    ((abgr and 0xFF) shl 16) or
                    ((abgr ushr 16) and 0xFF)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun roundUp(value: Int, step: Int): Int = ((value + step - 1) / step) * step

    private companion object {
        // Strip widths are rounded up so the reader is rarely re-created between scans.
        const val WIDTH_STEP_PX = 64
        const val MAX_IMAGES = 2
    }
}
