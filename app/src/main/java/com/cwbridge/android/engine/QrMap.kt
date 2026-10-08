package com.cwbridge.android.engine

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Encode text as a QR bit matrix and pack for Roblox paste-back.
 *
 * Wire format returned to the game:
 *   `<size 1-5>.<raw colormap>`
 * where colormap is a continuous row-major string of `0`/`1`
 * (dark module = 1, light = 0). Length is always N*N; Roblox can
 * take `math.floor(math.sqrt(#bits))` as module width.
 *
 * `size` is a coarse tier derived from QR version (1…5), for UI scale.
 */
object QrMap {

    data class Result(
        /** Coarse scale tier 1…5 for Roblox rendering. */
        val size: Int,
        /** Module count on one side (21, 25, …). */
        val modules: Int,
        /** Continuous 0/1 string, length = modules*modules. */
        val colorMap: String,
    ) {
        /** Payload pasted into the game: `size.colormap`. */
        fun wire(): String = "$size.$colorMap"
    }

    /**
     * @throws IllegalArgumentException if [text] is empty or cannot be encoded
     */
    fun encode(text: String): Result {
        val payload = text.trim()
        require(payload.isNotEmpty()) { "empty qr text" }

        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 0, // no quiet-zone padding in the bit grid
        )
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            /* width ignored when matrix is taken directly */ 0,
            0,
            hints,
        )
        val n = matrix.width
        require(n > 0 && n == matrix.height) { "bad matrix $n" }

        val sb = StringBuilder(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                sb.append(if (matrix.get(x, y)) '1' else '0')
            }
        }

        // QR version from module count: modules = 17 + 4*version
        val version = ((n - 17) / 4).coerceAtLeast(1)
        val size = version.coerceIn(1, 5)

        return Result(size = size, modules = n, colorMap = sb.toString())
    }
}
