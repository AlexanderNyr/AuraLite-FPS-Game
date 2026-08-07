package com.lanfps.shared

/**
 * Big-endian binary writer over a growable [ByteArray].
 *
 * Instances are meant to be reused (call [reset]) so that the server send loop and
 * the client network thread do not allocate per packet.
 */
class BinaryWriter(initialCapacity: Int = 1500) {

    var buffer: ByteArray = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
        private set

    /** Number of bytes written so far. */
    var position: Int = 0
        private set

    fun reset(): BinaryWriter {
        position = 0
        return this
    }

    private fun ensure(extra: Int) {
        val needed = position + extra
        if (needed <= buffer.size) return
        var newSize = buffer.size
        while (newSize < needed) newSize = newSize shl 1
        buffer = buffer.copyOf(newSize)
    }

    fun writeI8(v: Int): BinaryWriter {
        ensure(1)
        buffer[position++] = v.toByte()
        return this
    }

    fun writeU8(v: Int): BinaryWriter = writeI8(v and 0xFF)

    fun writeBool(v: Boolean): BinaryWriter = writeU8(if (v) 1 else 0)

    fun writeU16(v: Int): BinaryWriter {
        ensure(2)
        buffer[position++] = ((v ushr 8) and 0xFF).toByte()
        buffer[position++] = (v and 0xFF).toByte()
        return this
    }

    fun writeI16(v: Int): BinaryWriter = writeU16(v and 0xFFFF)

    fun writeI32(v: Int): BinaryWriter {
        ensure(4)
        buffer[position++] = ((v ushr 24) and 0xFF).toByte()
        buffer[position++] = ((v ushr 16) and 0xFF).toByte()
        buffer[position++] = ((v ushr 8) and 0xFF).toByte()
        buffer[position++] = (v and 0xFF).toByte()
        return this
    }

    fun writeI64(v: Long): BinaryWriter {
        ensure(8)
        var shift = 56
        while (shift >= 0) {
            buffer[position++] = ((v ushr shift) and 0xFF).toByte()
            shift -= 8
        }
        return this
    }

    fun writeF32(v: Float): BinaryWriter = writeI32(v.toRawBits())

    /**
     * Writes a UTF-8 string prefixed with an unsigned byte length.
     * Strings longer than [maxBytes] are truncated on a safe byte boundary.
     */
    fun writeString(s: String, maxBytes: Int = 255): BinaryWriter {
        var bytes = s.toByteArray(Charsets.UTF_8)
        val cap = if (maxBytes > 255) 255 else maxBytes
        if (bytes.size > cap) {
            // Trim without splitting a multi-byte sequence.
            var end = cap
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            bytes = bytes.copyOf(end)
        }
        writeU8(bytes.size)
        ensure(bytes.size)
        System.arraycopy(bytes, 0, buffer, position, bytes.size)
        position += bytes.size
        return this
    }

    fun writeBytes(src: ByteArray, offset: Int = 0, length: Int = src.size): BinaryWriter {
        ensure(length)
        System.arraycopy(src, offset, buffer, position, length)
        position += length
        return this
    }

    /** Skips [n] zeroed bytes, returning the offset they start at (for back-patching). */
    fun reserve(n: Int): Int {
        ensure(n)
        val at = position
        java.util.Arrays.fill(buffer, at, at + n, 0)
        position += n
        return at
    }

    fun putU16At(offset: Int, v: Int) {
        buffer[offset] = ((v ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (v and 0xFF).toByte()
    }

    fun putI32At(offset: Int, v: Int) {
        buffer[offset] = ((v ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (v and 0xFF).toByte()
    }

    /** Copies the written region into a fresh array. */
    fun toByteArray(): ByteArray = buffer.copyOf(position)
}
