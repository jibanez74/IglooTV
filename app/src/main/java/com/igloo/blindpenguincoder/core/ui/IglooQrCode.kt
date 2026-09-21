package com.igloo.blindpenguincoder.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private const val DEFAULT_QR_CODE_SIZE = 216
private const val QR_CODE_QUIET_ZONE_MODULES = 4

/** Renders a local, decorative QR image. Adjacent content must describe its purpose. */
@Composable
fun IglooQrCode(
    value: String,
    modifier: Modifier = Modifier,
) {
    val image = remember(value) {
        encodeQrCode(value, DEFAULT_QR_CODE_SIZE).toBitmap().asImageBitmap()
    }

    Image(
        bitmap = image,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.None,
    )
}

internal fun encodeQrCode(
    value: String,
    size: Int = DEFAULT_QR_CODE_SIZE,
): BitMatrix {
    require(value.isNotBlank()) { "QR code value cannot be blank" }
    require(size > 0) { "QR code size must be positive" }

    return QRCodeWriter().encode(
        value,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QR_CODE_QUIET_ZONE_MODULES,
        ),
    )
}

private fun BitMatrix.toBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] = if (get(x, y)) QR_BLACK else QR_WHITE
        }
    }

    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private const val QR_BLACK = 0xFF000000.toInt()
private const val QR_WHITE = 0xFFFFFFFF.toInt()
