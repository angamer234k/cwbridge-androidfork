package com.cwbridge.android

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Tiny local HTTP server: GET /status, POST /invoke */
class LocalHttpServer(
    private val port: Int = 8765,
    private val onInvoke: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null

    fun isRunning(): Boolean = running.get()
    fun port(): Int = port

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "cwbridge-http", isDaemon = true) {
            try {
                ServerSocket(port).use { ss ->
                    server = ss
                    LogBuffer.i("Server", "listening on http://127.0.0.1:$port")
                    while (running.get()) {
                        try {
                            val socket = ss.accept()
                            thread(isDaemon = true) { handle(socket) }
                        } catch (_: Exception) {
                            if (!running.get()) break
                        }
                    }
                }
            } catch (t: Throwable) {
                LogBuffer.e("Server", "failed: ${t.message}")
                running.set(false)
            } finally {
                server = null
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { server?.close() } catch (_: Exception) {}
        server = null
        LogBuffer.i("Server", "stopped")
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = OutputStreamWriter(s.getOutputStream())
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            val body = if (method == "POST") {
                val buf = StringBuilder()
                while (reader.ready()) {
                    val c = reader.read()
                    if (c < 0) break
                    buf.append(c.toChar())
                }
                buf.toString()
            } else ""

            val (code, contentType, content) = when {
                path.startsWith("/status") -> Triple(
                    200, "text/plain",
                    "CWBridge local server\nstate=${BridgeStatus.state}\ndetail=${BridgeStatus.detail}\n",
                )
                path.startsWith("/invoke") && method == "POST" -> {
                    val payload = body.trim()
                    if (payload.isNotEmpty()) {
                        onInvoke(if (payload.contains("invoke|")) payload else "invoke|$payload")
                        Triple(200, "text/plain", "ok\n")
                    } else Triple(400, "text/plain", "empty body\n")
                }
                path == "/" || path.startsWith("/help") -> Triple(
                    200, "text/plain",
                    "CWBridge Local Server\nGET /status\nPOST /invoke\n",
                )
                else -> Triple(404, "text/plain", "not found\n")
            }
            val bytes = content.toByteArray()
            writer.write("HTTP/1.1 $code OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$content")
            writer.flush()
        }
    }
}
