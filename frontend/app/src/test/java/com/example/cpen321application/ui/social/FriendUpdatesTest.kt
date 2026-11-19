package com.example.cpen321application.ui.social

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class FriendUpdatesTest {
    private class TestSocket(private val original: Request, val listener: WebSocketListener) : WebSocket {
        var cancelled = false
        override fun request() = original
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
        fun message(type: String) = listener.onMessage(this, "{\"type\":\"$type\"}")
    }

    private class TestFactory : WebSocket.Factory {
        val connections = Channel<TestSocket>(Channel.UNLIMITED)
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            TestSocket(request, listener).also { connections.trySend(it) }
    }

    @Test fun readyAndMutationRefreshAndCancellationClosesSocket() = runBlocking {
        withTimeout(5000) {
            val factory = TestFactory()
            val updates = Channel<FriendUpdate>(Channel.UNLIMITED)
            val job = launch { friendUpdates(factory, "ws://localhost/api/friends/events").collect { updates.send(it) } }
            val socket = factory.connections.receive()
            socket.message("friends.ready")
            assertEquals(FriendUpdate.REFRESH, updates.receive())
            socket.message("unrelated")
            socket.listener.onMessage(socket, "invalid-json")
            socket.message("friends.changed")
            assertEquals(FriendUpdate.REFRESH, updates.receive())
            job.cancelAndJoin()
            assertTrue(socket.cancelled)
            assertTrue(updates.tryReceive().isFailure)
        }
    }

    @Test fun droppedConnectionReconnectsAndReadyReloadsMissedChanges() = runBlocking {
        withTimeout(5000) {
            val factory = TestFactory()
            val updates = Channel<FriendUpdate>(Channel.UNLIMITED)
            val job = launch { friendUpdates(factory, "ws://localhost/api/friends/events").collect { updates.send(it) } }
            val first = factory.connections.receive()
            first.listener.onFailure(first, IOException("offline"), null)
            assertEquals(FriendUpdate.RECONNECTING, updates.receive())
            val second = factory.connections.receive()
            assertTrue(first.cancelled)
            second.message("friends.ready")
            assertEquals(FriendUpdate.REFRESH, updates.receive())
            job.cancelAndJoin()
            assertTrue(second.cancelled)
        }
    }

    @Test fun expiredSessionStopsReconnectAndReportsAuthenticationRequired() = runBlocking {
        withTimeout(5000) {
            val factory = TestFactory()
            val updates = Channel<FriendUpdate>(Channel.UNLIMITED)
            val job = launch { friendUpdates(factory, "ws://localhost/api/friends/events").collect { updates.send(it) } }
            val socket = factory.connections.receive()
            socket.listener.onClosing(socket, 1008, "Session expired")
            assertEquals(FriendUpdate.UNAUTHORIZED, updates.receive())
            job.join()
            assertTrue(socket.cancelled)
            assertTrue(factory.connections.tryReceive().isFailure)
        }
    }
}
