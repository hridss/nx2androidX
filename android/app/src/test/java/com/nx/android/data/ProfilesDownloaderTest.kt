package com.nx.android.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 档案包的下载：完整的包落地；被掐断的包不算数（会自己再试一次，试完还没有就当失败）。
 */
class ProfilesDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val servers = mutableListOf<TinyServer>()

    private val payload = ByteArray(4096) { (it % 251).toByte() }

    @After
    fun closeServers() {
        servers.forEach { it.close() }
        servers.clear()
    }

    /** 一个只会说 HTTP 的小服务：它按 [reply] 回话，回完就断开 */
    private class TinyServer(private val reply: (attempt: Int) -> Reply) {

        /** 一次回话：声明多少字节、真的发多少字节（少发的那些就是被掐断的） */
        class Reply(val declared: Int, val sent: ByteArray)

        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val attempts = AtomicInteger(0)

        /** 这个服务被问过几次 */
        val count get() = attempts.get()

        fun url() = "http://127.0.0.1:${socket.localPort}/package.zip"

        fun close() = socket.close()

        init {
            Thread {
                while (true) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    val answer = reply(attempts.incrementAndGet())
                    try {
                        serve(client, answer)
                    } catch (e: IOException) {
                        // 客户端自己走了：这一问没有下文
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(client: Socket, answer: Reply) {
            client.use {
                readRequest(it.getInputStream())
                val out = it.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${answer.declared}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(answer.sent)
                out.flush()
            }
        }

        /** 读完请求头（到空行为止） */
        private fun readRequest(input: java.io.InputStream) {
            val head = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) break
                head.write(b)
                if (head.size() >= 4 && head.toString("ISO-8859-1").endsWith("\r\n\r\n")) break
            }
        }
    }

    private fun server(reply: (attempt: Int) -> TinyServer.Reply): TinyServer =
        TinyServer(reply).also { servers.add(it) }

    private fun dest() = File(folder.root, "import/profiles.zip")

    @Test
    fun `a whole package lands in place`() {
        val server = server { TinyServer.Reply(payload.size, payload) }
        val dest = dest()
        val progress = mutableListOf<Float>()
        val result = ProfilesDownloader.download(server.url(), dest, { progress.add(it) }, { false })
        assertTrue(result.isSuccess)
        assertArrayEquals(payload, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
        assertEquals(1, server.count)
        assertEquals(1f, progress.last(), 0.001f)
    }

    @Test
    fun `a package that was cut short is not a package`() {
        val server = server { TinyServer.Reply(payload.size + 100, payload) }
        val dest = dest()
        val result = ProfilesDownloader.download(server.url(), dest, {}, { false })
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("cut short") == true)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun `a package that was cut short is tried once more`() {
        val server = server { n ->
            if (n == 1) TinyServer.Reply(payload.size + 100, payload) else TinyServer.Reply(payload.size, payload)
        }
        val dest = dest()
        val result = ProfilesDownloader.download(server.url(), dest, {}, { false })
        assertTrue(result.isSuccess)
        assertArrayEquals(payload, dest.readBytes())
        assertEquals(2, server.count)
    }

    @Test
    fun `a package that is cut short twice is a failure`() {
        val server = server { TinyServer.Reply(payload.size + 100, payload) }
        val dest = dest()
        val result = ProfilesDownloader.download(server.url(), dest, {}, { false })
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("cut short") == true)
        assertEquals(2, server.count)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun `a download the player called off is not tried again`() {
        val server = server { TinyServer.Reply(payload.size + 100, payload) }
        val dest = dest()
        val result = ProfilesDownloader.download(server.url(), dest, {}, { true })
        assertTrue(result.isFailure)
        assertEquals(1, server.count)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }
}
