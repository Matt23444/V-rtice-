package com.example.screenrec

import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Servidor HTTP mínimo. El que mira solo abre la dirección en un navegador:
 *   /        página con el visor
 *   /video   imagen en vivo (MJPEG)
 *   /audio   audio en vivo (WAV sin fin)
 *   /info    datos para el visor
 * Todas las rutas exigen la clave `k` de la dirección.
 */
class StreamServer(
    private val viewerHtml: String,
    private val key: String,
    private val hasAudio: Boolean,
    private val sampleRate: Int,
    private val channels: Int,
) {
    private class Frame(val seq: Long, val ts: Long, val data: ByteArray)

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val frames = ArrayDeque<Frame>()
    private var seq = 0L

    private val audioClients = CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()
    private val sockets = CopyOnWriteArraySet<Socket>()
    private var server: ServerSocket? = null

    @Volatile private var running = false
    val videoClients = AtomicInteger(0)
    var port = 0
        private set

    fun start(): Int {
        var s: ServerSocket? = null
        for (p in 8080..8090) {
            try {
                s = ServerSocket(p)
                break
            } catch (ex: IOException) {
            }
        }
        val ss = s ?: throw IOException("No hay un puerto libre")
        server = ss
        port = ss.localPort
        running = true
        thread(name = "stream-accept", isDaemon = true) {
            while (running) {
                val c = try { ss.accept() } catch (ex: Exception) { break }
                sockets.add(c)
                thread(name = "stream-client", isDaemon = true) { handle(c) }
            }
        }
        return port
    }

    fun stop() {
        running = false
        try { server?.close() } catch (ex: Exception) {}
        lock.lock()
        try { cond.signalAll() } finally { lock.unlock() }
        for (s in sockets) { try { s.close() } catch (ex: Exception) {} }
        sockets.clear()
        audioClients.clear()
    }

    fun pushFrame(data: ByteArray) {
        val now = System.currentTimeMillis()
        lock.lock()
        try {
            frames.addLast(Frame(++seq, now, data))
            while (frames.size > 1 && now - frames.first().ts > 4000) frames.removeFirst()
            cond.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun pushAudio(chunk: ByteArray) {
        for (q in audioClients) {
            if (!q.offer(chunk)) {
                q.poll()
                q.offer(chunk)
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 5000
            sock.tcpNoDelay = true
            val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val first = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            sock.soTimeout = 0

            val parts = first.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                respond(sock, 405, "text/plain; charset=utf-8", "Método no permitido")
                return
            }
            val target = parts[1]
            val qi = target.indexOf('?')
            val path = if (qi < 0) target else target.substring(0, qi)
            val query = parseQuery(if (qi < 0) "" else target.substring(qi + 1))

            if (path == "/favicon.ico") {
                respond(sock, 404, "text/plain", "")
                return
            }
            if (query["k"] != key) {
                respond(sock, 403, "text/plain; charset=utf-8",
                    "Acceso denegado. Usa la dirección completa que muestra la app (incluye ?k=...).")
                return
            }
            when (path) {
                "/" -> respond(sock, 200, "text/html; charset=utf-8", viewerHtml)
                "/info" -> respond(sock, 200, "application/json", "{\"audio\":$hasAudio}")
                "/video" -> serveVideo(sock, (query["delay"]?.toIntOrNull() ?: 0).coerceIn(0, 3500))
                "/audio" -> if (hasAudio) serveAudio(sock) else respond(sock, 404, "text/plain", "Sin audio")
                else -> respond(sock, 404, "text/plain", "No encontrado")
            }
        } catch (ex: Exception) {
            // el espectador se desconectó
        } finally {
            try { sock.close() } catch (ex: Exception) {}
            sockets.remove(sock)
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (pair in q.split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            try { m[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8") } catch (ex: Exception) {}
        }
        return m
    }

    private fun respond(sock: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = sock.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun serveVideo(sock: Socket, delayMs: Int) {
        val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\nPragma: no-cache\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.flush()
        videoClients.incrementAndGet()
        try {
            var last = -1L
            while (running && !sock.isClosed) {
                val f = nextFrame(last, delayMs) ?: continue
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.data.size}\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1))
                out.write(f.data)
                out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                last = f.seq
            }
        } finally {
            videoClients.decrementAndGet()
        }
    }

    /** Devuelve el fotograma más reciente que ya "tenga" [delayMs] de antigüedad y sea nuevo. */
    private fun nextFrame(last: Long, delayMs: Int): Frame? {
        lock.lock()
        try {
            val deadline = System.nanoTime() + 1_000_000_000L
            while (running) {
                val target = System.currentTimeMillis() - delayMs
                var cand: Frame? = null
                for (f in frames) {
                    if (delayMs <= 0 || f.ts <= target) cand = f else break
                }
                if (cand != null && cand.seq > last) return cand
                if (System.nanoTime() > deadline) return null
                cond.await(30, TimeUnit.MILLISECONDS)
            }
            return null
        } finally {
            lock.unlock()
        }
    }

    private fun serveAudio(sock: Socket) {
        val out = BufferedOutputStream(sock.getOutputStream(), 16 * 1024)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nCache-Control: no-cache, no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.write(wavHeader())
        out.flush()
        val q = ArrayBlockingQueue<ByteArray>(48)
        audioClients.add(q)
        try {
            while (running && !sock.isClosed) {
                val c = q.poll(500, TimeUnit.MILLISECONDS) ?: continue
                out.write(c)
                out.flush()
            }
        } finally {
            audioClients.remove(q)
        }
    }

    /** Cabecera WAV de longitud indefinida (PCM de 16 bits). */
    private fun wavHeader(): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(-1); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort())
        b.putInt(sampleRate); b.putInt(sampleRate * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(-1)
        return b.array()
    }
}
