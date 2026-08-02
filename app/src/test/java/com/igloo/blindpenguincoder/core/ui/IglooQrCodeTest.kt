package com.igloo.blindpenguincoder.core.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.ResultMetadataType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class IglooQrCodeTest {

    @Test
    fun `generated QR decodes to the exact approval URL with medium error correction`() {
        val approvalUrl = "http://[2001:db8::1]:8080/settings/account"
        val matrix = encodeQrCode(approvalUrl)

        val decoded = QRCodeReader().decode(matrix.toBinaryBitmap())

        assertEquals(approvalUrl, decoded.text)
        assertEquals("M", decoded.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
    }

    private fun BitMatrix.toBinaryBitmap(): BinaryBitmap {
        val pixels = IntArray(width * height) { index ->
            if (get(index % width, index / width)) BLACK else WHITE
        }
        return BinaryBitmap(
            HybridBinarizer(RGBLuminanceSource(width, height, pixels)),
        )
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
