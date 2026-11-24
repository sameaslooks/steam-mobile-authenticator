package io.github.sma.core.proto

import java.io.ByteArrayOutputStream

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LEN = 2
private const val WIRE_FIXED32 = 5

/** Minimal protobuf wire-format encoder. All methods return this for chaining. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        writeTag(field, WIRE_VARINT)
        writeRawVarint(value)
        return this
    }

    fun bool(field: Int, v: Boolean): ProtoWriter = varint(field, if (v) 1L else 0L)

    fun string(field: Int, s: String): ProtoWriter = bytes(field, s.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, b: ByteArray): ProtoWriter {
        writeTag(field, WIRE_LEN)
        writeRawVarint(b.size.toLong())
        out.write(b)
        return this
    }

    fun fixed64(field: Int, v: Long): ProtoWriter {
        writeTag(field, WIRE_FIXED64)
        for (i in 0 until 8) {
            out.write((v ushr (8 * i)).toInt() and 0xFF)
        }
        return this
    }

    fun float32(field: Int, v: Float): ProtoWriter {
        writeTag(field, WIRE_FIXED32)
        val bits = v.toRawBits()
        for (i in 0 until 4) {
            out.write((bits ushr (8 * i)) and 0xFF)
        }
        return this
    }

    fun message(field: Int, m: ByteArray): ProtoWriter = bytes(field, m)

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeTag(field: Int, wireType: Int) {
        require(field > 0) { "Field number must be positive: $field" }
        writeRawVarint((field.toLong() shl 3) or wireType.toLong())
    }

    private fun writeRawVarint(value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

/** Minimal protobuf wire-format decoder with last-value-wins semantics for singular fields. */
class ProtoMessage private constructor(private val fields: List<Field>) {

    private class Field(
        val number: Int,
        val wireType: Int,
        val scalar: Long,
        val payload: ByteArray?,
    )

    fun varint(f: Int): Long? =
        fields.lastOrNull { it.number == f && it.wireType == WIRE_VARINT }?.scalar

    fun bool(f: Int): Boolean? = varint(f)?.let { it != 0L }

    fun string(f: Int): String? = bytes(f)?.toString(Charsets.UTF_8)

    fun bytes(f: Int): ByteArray? =
        fields.lastOrNull { it.number == f && it.wireType == WIRE_LEN }?.payload

    fun fixed64(f: Int): Long? =
        fields.lastOrNull { it.number == f && it.wireType == WIRE_FIXED64 }?.scalar

    fun float32(f: Int): Float? =
        fields.lastOrNull { it.number == f && it.wireType == WIRE_FIXED32 }
            ?.let { Float.fromBits(it.scalar.toInt()) }

    fun message(f: Int): ProtoMessage? = bytes(f)?.let { parse(it) }

    fun messages(f: Int): List<ProtoMessage> =
        fields.filter { it.number == f && it.wireType == WIRE_LEN }
            .map { parse(it.payload!!) }

    /** Supports both unpacked repeated varints and packed (length-delimited) varints. */
    fun varints(f: Int): List<Long> {
        val result = mutableListOf<Long>()
        for (field in fields) {
            if (field.number != f) continue
            when (field.wireType) {
                WIRE_VARINT -> result.add(field.scalar)
                WIRE_LEN -> {
                    val cursor = Cursor(field.payload!!)
                    while (cursor.hasMore()) result.add(cursor.readVarint())
                }
            }
        }
        return result
    }

    companion object {
        /** @throws IllegalArgumentException on malformed or truncated input. */
        fun parse(bytes: ByteArray): ProtoMessage {
            val cursor = Cursor(bytes)
            val list = mutableListOf<Field>()
            while (cursor.hasMore()) {
                val tag = cursor.readVarint()
                val number = tag ushr 3
                require(number in 1L..Int.MAX_VALUE.toLong()) { "Invalid field number: $number" }
                val n = number.toInt()
                when (val wire = (tag and 7L).toInt()) {
                    WIRE_VARINT -> list.add(Field(n, wire, cursor.readVarint(), null))
                    WIRE_FIXED64 -> list.add(Field(n, wire, cursor.readFixed(8), null))
                    WIRE_LEN -> {
                        val len = cursor.readVarint()
                        list.add(Field(n, wire, 0L, cursor.readBytes(len)))
                    }
                    WIRE_FIXED32 -> list.add(Field(n, wire, cursor.readFixed(4), null))
                    else -> throw IllegalArgumentException("Unsupported wire type: $wire")
                }
            }
            return ProtoMessage(list)
        }
    }
}

private class Cursor(private val buf: ByteArray) {
    private var pos = 0

    fun hasMore(): Boolean = pos < buf.size

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < buf.size) { "Truncated varint" }
            require(shift < 64) { "Varint too long" }
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
    }

    fun readFixed(n: Int): Long {
        require(buf.size - pos >= n) { "Truncated fixed-width value" }
        var result = 0L
        for (i in 0 until n) {
            result = result or ((buf[pos + i].toLong() and 0xFFL) shl (8 * i))
        }
        pos += n
        return result
    }

    fun readBytes(len: Long): ByteArray {
        require(len >= 0 && len <= (buf.size - pos).toLong()) { "Truncated length-delimited value" }
        val size = len.toInt()
        val result = buf.copyOfRange(pos, pos + size)
        pos += size
        return result
    }
}
