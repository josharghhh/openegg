package com.openegg.app.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A bidirectional byte stream to the drone side (TCP today; USB/serial could slot in later). */
interface ByteTransport : AutoCloseable {
    val description: String
    suspend fun open()
    /** Blocks until data arrives; returns -1 at end of stream. */
    suspend fun read(buffer: ByteArray): Int
    suspend fun write(bytes: ByteArray)
}

class TcpTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 3000,
) : ByteTransport {
    override val description = "tcp://$host:$port"

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        (input ?: error("not open")).read(buffer)
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: error("not open")
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}
