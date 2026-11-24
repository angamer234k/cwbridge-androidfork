package com.cwbridge.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvokeProtocolTest {
    @Test
    fun missingMalformedAndReservedIdsAreIgnored() {
        assertNull(InvokeProtocol.parseRequest("Info invoke|save.key.value"))
        assertNull(InvokeProtocol.parseRequest("Info invoke|save.key.value||"))
        assertNull(InvokeProtocol.parseRequest("Info invoke|save.key.value||bad.id"))
        assertNull(InvokeProtocol.parseRequest("Info invoke|save.key.value||0"))
    }

    @Test
    fun commandPayloadMayContainTheReplyMarkerText() {
        val parsed = InvokeProtocol.parseRequest("Info invoke|echo.cwbridge|hello||req_123")
        assertEquals("echo", parsed?.command)
        assertEquals("cwbridge|hello", parsed?.data1)
    }

    @Test
    fun validRequestIsParsedWithItsId() {
        val parsed = InvokeProtocol.parseRequest("Info invoke|load.profile.weather.rbx||req_123")

        assertEquals("load", parsed?.command)
        assertEquals("profile", parsed?.data1)
        assertEquals("weather.rbx", parsed?.data2)
        assertEquals("req_123", parsed?.requestId)
    }

    @Test
    fun prioritiesFollowTheCatWebQueueOrder() {
        assertEquals(0, InvokeProtocol.priorityFor("save"))
        assertEquals(0, InvokeProtocol.priorityFor("load"))
        assertEquals(0, InvokeProtocol.priorityFor("storeinfo"))
        assertEquals(1, InvokeProtocol.priorityFor("setlimit"))
        assertEquals(2, InvokeProtocol.priorityFor("weather"))
        assertEquals(3, InvokeProtocol.priorityFor("ai"))
        assertEquals(1, InvokeProtocol.priorityFor("tap"))
    }

    @Test
    fun ackOnlyRequiresMatchingRequestId() {
        val ack = "Info invoke||ACK||24493351697"

        assertTrue(InvokeProtocol.ackMatches(ack, "24493351697", "0.hello wo"))
        assertFalse(InvokeProtocol.ackMatches(ack, "other", "0.hello wo"))
    }

    @Test
    fun extendedAckAlsoValidatesOptionalTenCharacterPrefix() {
        val prefix = "0.hello wo"
        val ack = "Info invoke||ACK||req_123||0.hello world"

        assertTrue(InvokeProtocol.ackMatches(ack, "req_123", prefix))
        assertFalse(InvokeProtocol.ackMatches(ack, "other", prefix))
        assertFalse(InvokeProtocol.ackMatches(ack, "req_123", "0.goodbye "))
        assertFalse(InvokeProtocol.ackMatches("Info invoke||ACK||req_123", "req_123", prefix))
        assertTrue(InvokeProtocol.ackMatches("Info invoke||ACK||req_123||0.hello \r\n", "req_123", "0.hello "))
    }
}
