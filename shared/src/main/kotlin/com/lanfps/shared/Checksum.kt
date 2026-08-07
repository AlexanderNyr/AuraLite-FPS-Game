package com.lanfps.shared

import java.util.zip.CRC32

/**
 * CRC32 over the packet payload. Guards against corrupted datagrams and against
 * foreign traffic that happens to land on our port with a matching magic number.
 *
 * A [CRC32] instance is kept per thread: the server receive loop, the server send
 * loop and the client network thread each get their own, so no locking is needed
 * and no allocation happens per packet.
 */
object Checksum {

    private val threadLocalCrc: ThreadLocal<CRC32> = ThreadLocal.withInitial { CRC32() }

    /** CRC32 of `data[offset, offset+length)` as a 32-bit int. */
    fun crc32(data: ByteArray, offset: Int, length: Int): Int {
        val crc = threadLocalCrc.get()
        crc.reset()
        if (length > 0) crc.update(data, offset, length)
        return crc.value.toInt()
    }

    fun crc32(data: ByteArray): Int = crc32(data, 0, data.size)
}
