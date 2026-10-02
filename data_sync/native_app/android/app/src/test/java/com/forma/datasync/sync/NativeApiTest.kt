package com.forma.datasync.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeApiTest {
    @Test(timeout = 10_000) fun successfulRequestReturnsJsonAndIncludesTheNativeSession() {
        val headers = AtomicReference<String>()
        val response = "{\"user\":{\"id\":\"user\"}}"
        LocalHttpServer { socket, request ->
            headers.set(request)
            socket.writeResponse("HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response")
        }.use { server ->
            val result = runBlocking { NativeApi(server.url, "session").request("/api/native/me") }
            assertEquals("user", result.getJSONObject("user").getString("id"))
            assertTrue(headers.get().contains("Authorization: Bearer session"))
        }
    }

    @Test(timeout = 10_000) fun cancellationDisconnectsARequestWaitingForResponseHeaders() {
        verifyCancellation(sendHeaders = false)
    }

    @Test(timeout = 10_000) fun cancellationDisconnectsARequestWaitingForResponseBody() {
        verifyCancellation(sendHeaders = true)
    }

    private fun verifyCancellation(sendHeaders: Boolean) {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = LocalHttpServer { socket, _ ->
            if (sendHeaders) socket.writeResponse("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{")
            received.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        try {
            runBlocking {
                val request = async(Dispatchers.Default) {
                    NativeApi(server.url, "session").request("/api/native/me")
                }
                withContext(Dispatchers.IO) { assertTrue(received.await(3, TimeUnit.SECONDS)) }
                val started = System.nanoTime()
                request.cancelAndJoin()
                assertTrue("Cancellation waited for the server response", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
            }
        } finally {
            release.countDown()
            server.close()
        }
    }

    @Test(timeout = 10_000) fun redirectCannotForwardTheSessionToAnotherEndpoint() {
        val redirectedRequests = AtomicInteger()
        val headers = AtomicReference<String>()
        LocalHttpServer { socket, request ->
            if (request.startsWith("POST /api/native/batches ")) {
                headers.set(request)
                socket.writeResponse("HTTP/1.1 307 Temporary Redirect\r\nLocation: /untrusted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            } else {
                redirectedRequests.incrementAndGet()
                socket.writeResponse("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
            }
        }.use { server ->
            runBlocking {
                try {
                    NativeApi(server.url, "session").request("/api/native/batches", JSONObject().put("batch_id", "batch"))
                    fail("Redirects must be rejected")
                } catch (error: ApiException) {
                    assertEquals(307, error.status)
                }
            }
            assertTrue(headers.get().contains("Authorization: Bearer session"))
            assertEquals(0, redirectedRequests.get())
        }
    }

    private fun Socket.writeResponse(response: String) {
        getOutputStream().apply { write(response.toByteArray(Charsets.UTF_8)); flush() }
    }

    /** Real sockets exercise cancellation without introducing an Android test server dependency. */
    private class LocalHttpServer(handler: (Socket, String) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val url = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.submit {
                while (!listener.isClosed) {
                    try {
                        listener.accept().use { socket ->
                            socket.soTimeout = 3_000
                            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                            val request = StringBuilder()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                request.append(line).append('\n')
                            }
                            handler(socket, request.toString())
                        }
                    } catch (_: Exception) {
                        // Closing the fixture or cancelling the client terminates its socket.
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
