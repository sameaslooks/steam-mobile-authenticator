package io.github.sma.core.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoTest {

    @Test
    fun varintRoundtrip() {
        val bytes = ProtoWriter()
            .varint(1, 0L)
            .varint(2, 300L)
            .varint(3, -500L)
            .varint(4, Long.MAX_VALUE)
            .varint(5, -1L)
            .toByteArray()
        val msg = ProtoMessage.parse(bytes)
        assertEquals(0L, msg.varint(1))
        assertEquals(300L, msg.varint(2))
        assertEquals(-500L, msg.varint(3))
        assertEquals(Long.MAX_VALUE, msg.varint(4))
        assertEquals(-1L, msg.varint(5))
    }

    @Test
    fun negativeVarintUsesTenBytes() {
        val bytes = ProtoWriter().varint(1, -1L).toByteArray()
        // 1 tag byte + 10 varint bytes
        assertEquals(11, bytes.size)
    }

    @Test
    fun boolRoundtrip() {
        val bytes = ProtoWriter().bool(1, true).bool(2, false).toByteArray()
        val msg = ProtoMessage.parse(bytes)
        assertTrue(msg.bool(1)!!)
        assertFalse(msg.bool(2)!!)
        assertNull(msg.bool(3))
    }

    @Test
    fun fixed64Roundtrip() {
        val bytes = ProtoWriter().fixed64(1, 76561198000000000L).toByteArray()
        assertEquals(76561198000000000L, ProtoMessage.parse(bytes).fixed64(1))
    }

    @Test
    fun stringRoundtrip() {
        val bytes = ProtoWriter().string(1, "héllo Steam").toByteArray()
        assertEquals("héllo Steam", ProtoMessage.parse(bytes).string(1))
    }

    @Test
    fun bytesRoundtrip() {
        val payload = byteArrayOf(0, 1, 2, 127, -128, -1)
        val bytes = ProtoWriter().bytes(1, payload).toByteArray()
        assertArrayEquals(payload, ProtoMessage.parse(bytes).bytes(1))
    }

    @Test
    fun float32Roundtrip() {
        val bytes = ProtoWriter().float32(1, 1.5f).toByteArray()
        assertEquals(1.5f, ProtoMessage.parse(bytes).float32(1)!!, 0f)
    }

    @Test
    fun nestedMessageRoundtrip() {
        val inner = ProtoWriter().varint(1, 42L).string(2, "inner").toByteArray()
        val outer = ProtoWriter().message(5, inner).toByteArray()
        val nested = ProtoMessage.parse(outer).message(5)!!
        assertEquals(42L, nested.varint(1))
        assertEquals("inner", nested.string(2))
    }

    @Test
    fun repeatedMessages() {
        val a = ProtoWriter().varint(1, 1L).toByteArray()
        val b = ProtoWriter().varint(1, 2L).toByteArray()
        val bytes = ProtoWriter().message(3, a).message(3, b).toByteArray()
        val list = ProtoMessage.parse(bytes).messages(3)
        assertEquals(listOf(1L, 2L), list.map { it.varint(1) })
    }

    @Test
    fun packedVarints() {
        // packed encoding of [1, 300]: 0x01, 0xAC 0x02
        val packed = byteArrayOf(0x01, 0xAC.toByte(), 0x02)
        val bytes = ProtoWriter().bytes(4, packed).toByteArray()
        assertEquals(listOf(1L, 300L), ProtoMessage.parse(bytes).varints(4))
    }

    @Test
    fun unpackedVarints() {
        val bytes = ProtoWriter().varint(4, 1L).varint(4, 300L).toByteArray()
        assertEquals(listOf(1L, 300L), ProtoMessage.parse(bytes).varints(4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedLengthDelimitedThrows() {
        // field 1, wire type 2, length 5, but only 2 payload bytes
        ProtoMessage.parse(byteArrayOf(0x0A, 0x05, 0x01, 0x02))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedVarintThrows() {
        // field 1, varint, continuation bit set on last byte
        ProtoMessage.parse(byteArrayOf(0x08, 0x80.toByte()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedFixed64Throws() {
        ProtoMessage.parse(byteArrayOf(0x09, 0x01, 0x02))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedWireTypeThrows() {
        // field 1, wire type 3 (start group)
        ProtoMessage.parse(byteArrayOf(0x0B))
    }
}
