package com.lanfps.shared

/** Thrown when a packet is truncated or otherwise malformed. Callers drop the packet. */
class ProtocolException(message: String) : RuntimeException(message)

/**
 * Big-endian binary reader over a [ByteArray] slice.
 *
 * Reusable via [wrap] so the receive loop does not allocate. Every read is bounds
 * checked: a malicious or corrupted datagram throws [ProtocolException] instead of
 * producing garbage or an AIOOBE deep inside game logic.
 */
class BinaryReader(
    var buffer: ByteArray = ByteArray(0),
    offset: Int = 0,
    length: Int = buffer.size,
) {
    var position: Int = offset
        private set

    var limit: Int = offset + length
        private set

    fun wrap(buf: ByteArray, offset: Int = 0, length: Int = buf.size): BinaryReader {
        buffer = buf
        position = offset
        limit = offset + length
        return this
    }

    fun seek(newPosition: Int): BinaryReader {
        if (newPosition < 0 || newPosition > limit) {
            throw ProtocolException("seek out of range: $newPosition (limit=$limit)")
        }
        position = newPosition
        return this
    }

    fun remaining(): Int = limit - position

    fun hasRemaining(): Boolean = position < limit

    private fun require(n: Int) {
        if (position + n > limit) {
            throw ProtocolException("truncated packet: need $n byte(s) at $position, limit=$limit")
        }
    }

    fun readI8(): Byte {
        require(1)
        return buffer[position++]
    }

    fun readU8(): Int {
        require(1)
        return buffer[position++].toInt() and 0xFF
    }

    fun readBool(): Boolean = readU8() != 0

    fun readU16(): Int {
        require(2)
        val a = buffer[position++].toInt() and 0xFF
        val b = buffer[position++].toInt() and 0xFF
        return (a shl 8) or b
    }

    /** Reads a 16-bit value and sign-extends it. */
    fun readI16(): Int {
        val v = readU16()
        return if (v >= 0x8000) v - 0x10000 else v
    }

    fun readI32(): Int {
        require(4)
        val a = buffer[position++].toInt() and 0xFF
        val b = buffer[position++].toInt() and 0xFF
        val c = buffer[position++].toInt() and 0xFF
        val d = buffer[position++].toInt() and 0xFF
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    fun readI64(): Long {
        require(8)
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (buffer[position++].toLong() and 0xFF)
        }
        return v
    }

    fun readF32(): Float = Float.fromBits(readI32())

    fun readString(): String {
        val len = readU8()
        require(len)
        val s = String(buffer, position, len, Charsets.UTF_8)
        position += len
        return s
    }

    fun readBytes(dest: ByteArray, offset: Int = 0, length: Int = dest.size): BinaryReader {
        require(length)
        System.arraycopy(buffer, position, dest, offset, length)
        position += length
        return this
    }

    fun skip(n: Int): BinaryReader {
        require(n)
        position += n
        return this
    }
}
