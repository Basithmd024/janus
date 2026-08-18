package com.janus.app

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.janus.app.core.Packet
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class JanusUnitTest {

    private val gson = Gson()

    @Test
    fun testPacketSerialization() {
        val payload = JsonObject().apply {
            addProperty("battery_level", 85)
            addProperty("is_charging", true)
            addProperty("signal_level", 4)
        }
        val packet = Packet(
            type = "device.status",
            id = "test-uuid-9999",
            timestamp = 1723980000L,
            payload = payload
        )

        val json = gson.toJson(packet)
        assertTrue(json.contains("device.status"))
        assertTrue(json.contains("test-uuid-9999"))

        val deserialized = gson.fromJson(json, Packet::class.java)
        assertEquals("device.status", deserialized.type)
        assertEquals("test-uuid-9999", deserialized.id)
        assertEquals(85, deserialized.payload.get("battery_level").asInt)
        assertTrue(deserialized.payload.get("is_charging").asBoolean)
    }

    @Test
    fun testSha256DigestCalculation() {
        val sampleInput = "Janus Secure Bridge Node 2026"
        val md = MessageDigest.getInstance("SHA-256")
        val digestBytes = md.digest(sampleInput.toByteArray(Charsets.UTF_8))
        val hexString = digestBytes.joinToString("") { "%02x".format(it) }

        assertEquals(64, hexString.length)
        assertTrue(hexString.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun testExponentialBackoffDelays() {
        fun calculateDelay(attempt: Int): Long {
            return when {
                attempt <= 3 -> 2000L
                attempt <= 10 -> 4000L
                else -> 8000L
            }
        }

        assertEquals(2000L, calculateDelay(1))
        assertEquals(2000L, calculateDelay(3))
        assertEquals(4000L, calculateDelay(4))
        assertEquals(4000L, calculateDelay(10))
        assertEquals(8000L, calculateDelay(11))
    }
}
