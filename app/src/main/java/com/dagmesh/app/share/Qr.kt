package com.dagmesh.app.share

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object Qr {
    /** Metni QR koduna çevirir (koyu kare, açık zemin). */
    fun encode(text: String, size: Int = 720): ImageBitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix.get(x, y)) DARK else LIGHT
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Telefon kameralarının tanıdığı Wi‑Fi QR biçimi. */
    fun wifi(ssid: String, password: String): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
            .replace(":", "\\:").replace("\"", "\\\"")
        return if (password.isEmpty()) "WIFI:T:nopass;S:${esc(ssid)};;"
        else "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};;"
    }

    private const val DARK = 0xFF0A1220.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()
}
