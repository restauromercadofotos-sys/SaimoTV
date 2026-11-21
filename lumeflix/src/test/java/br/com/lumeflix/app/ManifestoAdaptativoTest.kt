package br.com.lumeflix.app

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestoAdaptativoTest {
    @Test fun entregaOMestreAoPlayerSemPreSelecionarQualidade() {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${http.address.port}"
        val consultas = AtomicInteger()
        http.createContext("/") { conn ->
            consultas.incrementAndGet()
            val resposta = if (conn.requestURI.path.endsWith(".json")) {
                """{"streams":[{"name":"Dublado","url":"$base/master.m3u8"}]}"""
            } else {
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=600000\n480.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4000000\n1080.m3u8"
            }.toByteArray()
            conn.sendResponseHeaders(200, resposta.size.toLong())
            conn.responseBody.use { it.write(resposta) }
        }
        http.start()
        try {
            assertEquals(listOf("dub" to "$base/master.m3u8"), Resolvedor404.buscarPor("Hollymovies", base, "series", "exemplo"))
            assertEquals(1, consultas.get())
        } finally {
            http.stop(0)
        }
    }
}
