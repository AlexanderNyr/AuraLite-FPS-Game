package com.lanfps.shared

/**
 * Packet framing shared by client and server.
 *
 * Every datagram is:
 *
 * ```
 * offset size field
 * 0      4    magic          0x4C414E46 ("LANF")
 * 4      1    protocolVersion
 * 5      1    packetType
 * 6      2    sequence            (u16, wraps)
 * 8      2    ack / lastReceived  (u16)
 * 10     2    payloadLength       (u16)
 * 12     4    checksum            CRC32 of the payload bytes
 * 16     ...  payload
 * ```
 *
 * A packet is rejected — silently, never fatally — when the magic, the version,
 * the declared length or the CRC32 does not match. That makes the port safe to
 * expose on a LAN where stray broadcast traffic is common.
 */
object Protocol {

    const val HEADER_SIZE: Int = 16

    private const val OFFSET_PAYLOAD_LENGTH = 10
    private const val OFFSET_CHECKSUM = 12

    /** Result of [parse]. Only [OK] means the reader is safe to use. */
    enum class ParseResult {
        OK,
        TOO_SHORT,
        TOO_LARGE,
        BAD_MAGIC,
        BAD_VERSION,
        BAD_LENGTH,
        BAD_CHECKSUM,
    }

    /** Decoded header fields. Reused by the receive loop to avoid allocation. */
    class Header {
        @JvmField var magic: Int = 0
        @JvmField var version: Int = 0
        @JvmField var type: Int = 0
        @JvmField var sequence: Int = 0
        @JvmField var ack: Int = 0
        @JvmField var payloadLength: Int = 0
        @JvmField var checksum: Int = 0

        override fun toString(): String =
            "Header(${PacketTypes.name(type)} v$version seq=$sequence ack=$ack len=$payloadLength)"
    }

    /**
     * Starts a packet: writes the header with placeholder length/checksum.
     * Follow with the payload, then call [end].
     */
    fun begin(w: BinaryWriter, type: Int, sequence: Int = 0, ack: Int = 0): BinaryWriter {
        w.reset()
        w.writeI32(GameConstants.MAGIC)
        w.writeU8(GameConstants.PROTOCOL_VERSION)
        w.writeU8(type)
        w.writeU16(sequence and 0xFFFF)
        w.writeU16(ack and 0xFFFF)
        w.writeU16(0)   // payloadLength placeholder
        w.writeI32(0)   // checksum placeholder
        return w
    }

    /**
     * Finishes a packet started by [begin]: back-patches payload length and CRC32.
     * @return the total datagram length in bytes.
     */
    fun end(w: BinaryWriter): Int {
        val total = w.position
        val payloadLength = total - HEADER_SIZE
        require(payloadLength >= 0) { "packet has no header" }
        require(payloadLength <= 0xFFFF) { "payload too large: $payloadLength" }
        w.putU16At(OFFSET_PAYLOAD_LENGTH, payloadLength)
        val crc = Checksum.crc32(w.buffer, HEADER_SIZE, payloadLength)
        w.putI32At(OFFSET_CHECKSUM, crc)
        return total
    }

    /** Overwrites a u16 field already present in a built datagram. */
    fun patchU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    /**
     * Recomputes the CRC32 of an already-built datagram. Needed when the sender
     * back-patches a per-recipient field (see
     * [Packets.SNAPSHOT_LAST_INPUT_OFFSET]) after [end] has run.
     */
    fun rechecksum(buffer: ByteArray, totalLength: Int) {
        val payloadLength = totalLength - HEADER_SIZE
        if (payloadLength < 0) return
        val crc = Checksum.crc32(buffer, HEADER_SIZE, payloadLength)
        buffer[OFFSET_CHECKSUM] = ((crc ushr 24) and 0xFF).toByte()
        buffer[OFFSET_CHECKSUM + 1] = ((crc ushr 16) and 0xFF).toByte()
        buffer[OFFSET_CHECKSUM + 2] = ((crc ushr 8) and 0xFF).toByte()
        buffer[OFFSET_CHECKSUM + 3] = (crc and 0xFF).toByte()
    }

    /**
     * Validates and decodes the header of a received datagram.
     *
     * On [ParseResult.OK] the [reader] is positioned at the first payload byte and
     * limited to the declared payload length.
     */
    fun parse(
        data: ByteArray,
        length: Int,
        header: Header,
        reader: BinaryReader,
    ): ParseResult {
        if (length < HEADER_SIZE) return ParseResult.TOO_SHORT
        if (length > GameConstants.MAX_PACKET_SIZE) return ParseResult.TOO_LARGE

        reader.wrap(data, 0, length)
        header.magic = reader.readI32()
        if (header.magic != GameConstants.MAGIC) return ParseResult.BAD_MAGIC

        header.version = reader.readU8()
        if (header.version != GameConstants.PROTOCOL_VERSION) return ParseResult.BAD_VERSION

        header.type = reader.readU8()
        header.sequence = reader.readU16()
        header.ack = reader.readU16()
        header.payloadLength = reader.readU16()
        header.checksum = reader.readI32()

        if (HEADER_SIZE + header.payloadLength > length) return ParseResult.BAD_LENGTH

        val crc = Checksum.crc32(data, HEADER_SIZE, header.payloadLength)
        if (crc != header.checksum) return ParseResult.BAD_CHECKSUM

        // Restrict the reader to exactly the payload so a lying length cannot make
        // a decoder read into neighbouring datagram bytes still in the buffer.
        reader.wrap(data, HEADER_SIZE, header.payloadLength)
        return ParseResult.OK
    }
}
