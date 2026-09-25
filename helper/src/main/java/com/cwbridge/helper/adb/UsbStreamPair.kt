package com.cwbridge.helper.adb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Bulk IN/OUT streams over a claimed ADB USB interface. */
class UsbStreamPair(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) {
    val input: InputStream = object : InputStream() {
        private val buf = ByteArray(epIn.maxPacketSize.coerceAtLeast(4096))
        private var pos = 0
        private var end = 0

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n <= 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= end) {
                val n = connection.bulkTransfer(epIn, buf, buf.size, 10_000)
                if (n <= 0) return -1
                pos = 0
                end = n
            }
            val n = minOf(len, end - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var left = len
            while (left > 0) {
                val chunk = minOf(left, epOut.maxPacketSize.coerceAtLeast(4096))
                val slice = b.copyOfRange(offset, offset + chunk)
                val n = connection.bulkTransfer(epOut, slice, slice.size, 10_000)
                if (n < 0) throw IOException("USB bulk OUT failed: $n")
                offset += n
                left -= n
            }
        }
    }

    fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Throwable) {
        }
        connection.close()
    }
}
