package com.yomikai.overlayreader.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверка самодельного протобуф-кодека, которым общается Google Lens. */
class ProtoCodecTest {

    @Test
    fun `int32 and string roundtrip`() {
        val bytes = ProtoWriter().apply {
            writeInt32(1, 150)
            writeString(2, "привет")
        }.toByteArray()

        val reader = ProtoReader(bytes)
        val tags = mutableListOf<Pair<Int, Int>>()
        var i32 = 0
        var str = ""
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            when {
                field == 1 && wire == 0 -> i32 = reader.readVarint32()
                field == 2 && wire == 2 -> str = reader.readString()
                else -> error("unexpected $field/$wire")
            }
            tags += field to wire
        }
        assertEquals(150, i32)
        assertEquals("привет", str)
        assertEquals(listOf(1 to 0, 2 to 2), tags)
    }

    @Test
    fun `nested message preserves structure`() {
        val bytes = ProtoWriter().apply {
            writeMessage(3) { inner ->
                inner.writeInt32(1, 42)
                inner.writeString(2, "x")
            }
        }.toByteArray()

        val reader = ProtoReader(bytes)
        val rootTag = reader.readTag()
        assertEquals(3, rootTag ushr 3)
        assertEquals(2, rootTag and 0x7)
        val inner = ProtoReader(reader.readBytes())
        var f = -1
        var s = ""
        while (inner.hasRemaining()) {
            val tag = inner.readTag()
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 1 && wire == 0) f = inner.readVarint32()
            if (field == 2 && wire == 2) s = inner.readString()
        }
        assertEquals(42, f)
        assertEquals("x", s)
    }

    @Test
    fun `skipField handles nested fields`() {
        val bytes = ProtoWriter().apply {
            writeMessage(1) { inner ->
                inner.writeBytes(2, byteArrayOf(1, 2, 3))
                inner.writeInt32(9, 7)
            }
            writeInt32(2, 5)
        }.toByteArray()

        val reader = ProtoReader(bytes)
        val tag1 = reader.readTag()
        assertEquals(1, tag1 ushr 3)
        reader.skipField(tag1 and 0x7)
        val tag2 = reader.readTag()
        assertEquals(2, tag2 ushr 3)
        assertEquals(5, reader.readVarint32())
    }

    @Test
    fun `varint large uint64 roundtrip`() {
        val bytes = ProtoWriter().apply {
            writeUInt64(4, 1L shl 40)
        }.toByteArray()
        val reader = ProtoReader(bytes)
        assertEquals(4, reader.readTag() ushr 3)
        assertEquals(1L shl 40, reader.readVarint64())
    }

    @Test
    fun `reader rejects truncated length`() {
        val bytes = byteArrayOf(0x0A, 0x10, 0x01)
        val reader = ProtoReader(bytes)
        assertTrue(runCatching {
            reader.readTag()
            reader.readBytes()
        }.isFailure)
    }
}