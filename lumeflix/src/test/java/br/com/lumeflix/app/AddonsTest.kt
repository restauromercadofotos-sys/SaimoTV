package br.com.lumeflix.app

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AddonsTest {
    @Test fun manifestosCatalogosEFontes() = runBlocking {
        val dir = Files.createTempDirectory("addons").toFile()
        val pedidos = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        var nome = "Cinema"
        val master = "https://video.example/master.m3u8?k=a%2Fb"
        server.createContext("/") { x ->
            val path = x.requestURI.path
            pedidos += x.requestURI.toString()
            val prov = path.split('/')[1]
            val resources = when (prov) {
                "catalogo" -> """["catalog","meta"]"""
                "serie" -> """["catalog","meta",{"name":"stream","types":["series"],"idPrefixes":["ep:"]}]"""
                "tv" -> """["catalog","stream"]"""
                else -> """[{"name":"stream","types":["movie","series"],"idPrefixes":["tt"]}]"""
            }
            val body = when {
                path.endsWith("/manifest.json") -> """{"id":"org.$prov","name":"$nome","version":"1.0.0","resources":$resources,"types":${if (prov == "tv") """["tv"]""" else """["movie","series","channel"]"""},"catalogs":[{"id":"popular","type":"movie","extra":[{"name":"search"},{"name":"skip"}]}]}"""
                "/catalog/" in path -> """{"metas":[{"type":"movie","id":"tt123","name":"Teste"}]}"""
                "/meta/" in path -> """{"meta":{"id":"obra:9","type":"series","name":"Paradise","videos":[{"id":"ep:xyz","season":1,"episode":1}]}}"""
                prov == "fora" -> ""
                path.endsWith("/tt27444205:1:1.json") || path.endsWith("/tt123.json") ||
                    path.endsWith("/ep:xyz.json") || path.endsWith("/canal:1.json") ->
                    """{"streams":[{"url":"$master","name":"Dublado 1080p"},{"url":"$master"},{"infoHash":"abc"}]}"""
                else -> """{"streams":[]}"""
            }
            val bytes = body.toByteArray()
            x.sendResponseHeaders(if (body.isEmpty()) 503 else 200, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            Addons.iniciar(dir)
            val base = "http://127.0.0.1:${server.address.port}"
            fun link(p: String) = "$base/$p/config%2Fprivada/manifest.json?k=a%2Fb"
            assertEquals("https://x.test/a/manifest.json?k=x",Addon.normalizar("stremio://x.test/a/manifest.json?k=x"))
            assertTrue(runCatching { Addon.normalizar("file:///manifest.json") }.isFailure)
            Addons.importar(link("fontes"),false)
            assertTrue(Addons.instalados().isEmpty())
            val fontes = Addons.importar(link("fontes"),true)
            nome = "Nome atualizado"
            Addons.importar(link("fontes"),true)
            Addons.iniciar(dir)
            assertEquals("Nome atualizado",Addons.instalados().single().nome)
            Addons.importar(link("fora"),true)
            val cat = Addons.importar(link("catalogo"),true)
            val c = Addons.Catalogo(cat,cat.manifesto.getJSONArray("catalogs").getJSONObject(0))
            assertTrue(c.disponivel(true) && c.suporta("skip"))
            assertEquals("tt123",c.pagina("Um & Dois",12).single().getString("id"))
            assertTrue(pedidos.any { "config%2Fprivada/catalog/movie/popular/search=Um%20%26%20Dois&skip=12.json?k=a%2Fb" in it })
            assertFalse(cat.aceita("stream","movie","tt123"))
            assertFalse(fontes.aceita("stream","tv","tt123"))
            assertEquals(listOf("dub" to master), Addons.fontes("movie",listOf("tt123")))
            val total = pedidos.size
            Addons.fontes("movie",listOf("tt123"))
            assertEquals(total,pedidos.size)
            assertEquals("1080p",Resolvedor404.qualidade(master))
            val serie = Addons.importar(link("serie"),true)
            val ref = Addons.Ref(serie.chave,"series","obra:9")
            val video = Addons.meta(ref)!!.getJSONArray("videos").getJSONObject(0)
            val ep = Titulos.Ep(1,1,emptyMap(),video.getString("id"))
            assertEquals(listOf(master),Addons.fontesDoVideo(ref,ep)["dub"])
            assertTrue(Addons.fontes("series",listOf("ep:xyz"),cat.chave).isEmpty())
            assertTrue(runCatching { Addons.importar(link("tv"),true) }.isFailure)
            val antes = pedidos.size
            for (tipo in listOf("tv","channel")) {
                assertFalse(Addons.colecaoPermitida("stremio/antigo/$tipo/1"))
                assertTrue(Addons.fontesDoVideo(Addons.Ref(cat.chave,tipo,"canal:1"),null).isEmpty())
            }
            assertEquals(antes,pedidos.size)
            val drama = Addons.Ref(cat.chave,"series","tmdb:245927")
            val reality = drama.copy(id="tmdb:51025")
            assertEquals(listOf(master),Addons.fontesDoVideo(drama,Titulos.Ep(1,1,emptyMap()))["dub"])
            assertTrue(Addons.fontesDoVideo(reality,Titulos.Ep(1,1,emptyMap())).isEmpty())
            assertTrue(Addons.fontesDoVideo(drama,Titulos.Ep(1,2,emptyMap())).isEmpty())
            Addons.remover(fontes)
            assertTrue(Addons.fontes("movie",listOf("tt123")).isEmpty())
        } finally { server.stop(0); dir.deleteRecursively() }
        Unit
    }

    @Test fun fichasComHomonimos() {
        Generos.montar(sequenceOf("s\tParadise\t51025\t/errada.jpg\tReality",
            "s\tParadise Hotel (2005)\t51025\t/hotel.jpg\tReality"))
        try {
            assertEquals(245927,Generos.id("Paradise (2025)",true))
            assertEquals(51025,Generos.id("Paradise Hotel (2005)",true))
            assertNull(Generos.id("Paradise",true))
            assertNull(Generos.capa("Paradise (2025)",true))
            assertEquals("/hotel.jpg",Generos.capa("Paradise Hotel (2005)",true))
        } finally { Generos.limpar() }
    }
}
