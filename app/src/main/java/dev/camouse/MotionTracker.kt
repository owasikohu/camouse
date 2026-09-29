package dev.camouse

import androidx.camera.core.ImageProxy
import kotlin.math.abs

/** Block matching on the Y plane. Coordinates are sensor coordinates, in analysis pixels. */
class MotionTracker {
    data class Result(val dx: Int, val dy: Int, val confidence: Float, val detail: String)
    private var previous: ByteArray? = null
    private val size = 96
    private val search = 12

    fun reset() { previous = null }

    fun analyze(image: ImageProxy): Result? {
        if (image.width < size || image.height < size) { reset(); return null }
        val plane = image.planes[0]
        val buffer = plane.buffer
        val crop = ByteArray(size * size)
        val left = (image.width - size) / 2
        val top = (image.height - size) / 2
        for (y in 0 until size) for (x in 0 until size)
            crop[y * size + x] = buffer.get((top + y) * plane.rowStride + (left + x) * plane.pixelStride)
        val old = previous
        previous = crop
        if (old == null) return null

        // A featureless or underexposed desk cannot give a trustworthy displacement.
        var oldMean = 0L
        var newMean = 0L
        for (y in 16 until 80 step 2) for (x in 16 until 80 step 2) {
            oldMean += old[y * size + x].toInt() and 255
            newMean += crop[y * size + x].toInt() and 255
        }
        oldMean /= 1024
        newMean /= 1024
        var texture = 0L
        for (y in 16 until 80 step 2) for (x in 16 until 80 step 2) {
            val p = y * size + x
            val value = old[p].toInt() and 255
            texture += abs(value - (old[p + 1].toInt() and 255))
            texture += abs(value - (old[p + size].toInt() and 255))
        }
        if (texture / 1024 < 3) return Result(0, 0, 0f, "模様不足/ピンぼけ")

        var best = Long.MAX_VALUE
        var bx = 0; var by = 0
        val width = search * 2 + 1
        val scores = LongArray(width * width)
        val brightnessShift = (oldMean - newMean).toInt()
        for (dy in -search..search) for (dx in -search..search) {
            var score = 0L
            for (y in 16 until 80 step 2) for (x in 16 until 80 step 2) {
                val a = old[y * size + x].toInt() and 255
                val b = crop[(y + dy) * size + x + dx].toInt() and 255
                score += abs(a - b - brightnessShift)
            }
            scores[(dy + search) * width + dx + search] = score
            if (score < best) { best = score; bx = dx; by = dy }
        }
        var runnerUp = Long.MAX_VALUE
        for (dy in -search..search) for (dx in -search..search) {
            if (abs(dx - bx) > 2 || abs(dy - by) > 2)
                runnerUp = minOf(runnerUp, scores[(dy + search) * width + dx + search])
        }
        val confidence = ((runnerUp - best).toFloat() / runnerUp.coerceAtLeast(1)).coerceIn(0f, 1f)
        // A match at the search boundary may actually be a larger, unmeasured motion.
        if (abs(bx) == search || abs(by) == search) return Result(bx, by, 0f, "速すぎる")
        return Result(bx, by, confidence, if (confidence < 0.06f) "一致が曖昧" else "追跡中")
    }
}
