package br.com.lumeflix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun JSONArray?.objetos() = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
private fun JSONArray.textos() = (0 until length()).map { getString(it) }

internal class Addon(val link: String, val manifesto: JSONObject) {
    val chave = UUID.nameUUIDFromBytes(link.toByteArray()).toString()
    val nome = manifesto.getString("name")
    val tipos = manifesto.getJSONArray("types").textos()
    val recursos = manifesto.getJSONArray("resources").let { array ->
        (0 until array.length()).map { i -> array.optJSONObject(i) ?: JSONObject().put("name", array.getString(i)) }
    }

    init {
        require(nome.isNotBlank() && manifesto.getString("id").isNotBlank() && manifesto.getString("version").isNotBlank())
        require(recursos.all { resource ->
            resource.getString("name").isNotBlank() && listOf("idPrefixes","types").all { key ->
                !resource.has(key) || resource.getJSONArray(key).textos().all(String::isNotBlank)
            }
        })
        if (manifesto.has("idPrefixes")) manifesto.getJSONArray("idPrefixes").textos()
        manifesto.optJSONArray("catalogs").objetos().forEach {
            require(it.getString("id").isNotBlank() && it.getString("type").isNotBlank())
        }
    }

    fun aceita(recurso: String, tipo: String, id: String): Boolean = recursos.any {
        val prefixos = it.optJSONArray("idPrefixes") ?: manifesto.optJSONArray("idPrefixes")
        it.optString("name") == recurso && tipo in (it.optJSONArray("types")?.textos() ?: tipos) &&
            (recurso == "catalog" || prefixos == null || prefixos.textos().any(id::startsWith))
    }

    fun endpoint(recurso: String, tipo: String, id: String, extras: String? = null): String {
        val url = link.toHttpUrlOrNull()!!
        val b = url.newBuilder().removePathSegment(url.pathSegments.lastIndex)
            .addPathSegment(recurso).addPathSegment(tipo)
        if (extras == null) b.addPathSegment("$id.json")
        else b.addPathSegment(id).addEncodedPathSegment("$extras.json")
        return b.build().toString()
    }

    companion object {
        fun normalizar(valor: String): String {
            val raw = valor.trim()
            val url = (if (raw.startsWith("stremio://", true)) "https://" + raw.substringAfter("://") else raw).toHttpUrlOrNull()
            require(url != null && url.pathSegments.last() == "manifest.json" && url.fragment == null)
            return url.toString()
        }
    }
}

internal object Addons {
    private lateinit var pasta: java.io.File
    fun iniciar(context: Context) = iniciar(java.io.File(context.noBackupFilesDir,"stremio"))
    internal fun iniciar(dir: java.io.File) {
        pasta = dir
        dir.mkdirs()
        recarregar()
    }
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    @Volatile private var ativos: List<Addon> = emptyList()
    fun instalados(): List<Addon> = ativos
    @Synchronized private fun recarregar() {
        ativos = lerInstalados()
        Resolvedor404.invalidar()
    }
    private fun lerInstalados(): List<Addon> = pasta.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
        runCatching {
            val item = JSONObject(f.readText())
            Addon(item.getString("link"),item.getJSONObject("manifest"))
        }.getOrNull()
    }.sortedBy { it.nome }

    internal fun ler(url: String): JSONObject? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use {
            if (it.isSuccessful) JSONObject(it.body.string()) else null
        }
    } catch (_: Exception) { null }

    suspend fun importar(raw: String, salvar: Boolean): Addon = withContext(Dispatchers.IO) {
        val link = Addon.normalizar(raw)
        val a = Addon(link, requireNotNull(ler(link)))
        if (salvar) {
            val arquivo = java.io.File.createTempFile("manifest", ".tmp", pasta)
            try {
                arquivo.writeText(JSONObject().put("link", link).put("manifest", a.manifesto).toString())
                check(arquivo.renameTo(java.io.File(pasta,a.chave + ".json")))
            } finally { arquivo.delete() }
            recarregar()
        }
        a
    }
    fun remover(a: Addon) {
        java.io.File(pasta,a.chave + ".json").delete()
        synchronized(previews) { previews.keys.removeAll { it.startsWith("stremio/${a.chave}/") } }
        recarregar()
    }

    data class Ref(val addon: String, val tipo: String, val id: String) {
        val chave get() = "stremio/$addon/$tipo/$id"
        val idsPublicos get() = when (id) {
            "tmdb:245927", "tt27444205" -> if (tipo == "series") listOf("tmdb:245927","tt27444205") else listOf(id)
            else -> listOf(id)
        }
        val progresso get() = if (publico(id)) "stremio/$tipo/${idsPublicos.first()}" else
            "stremio/$addon/$tipo/" + UUID.nameUUIDFromBytes(id.toByteArray())
    }
    fun ref(alvo: Alvo): Ref? {
        val p = alvo.colecao.split('/', limit = 4)
        return if (p.size == 4 && p[0] == "stremio") Ref(p[1], p[2], p[3]) else null
    }

    internal fun publico(id: String) = id.matches(Regex("""(?:tt\d+|tmdb:\d+)(?::\d+:\d+)?"""))
    private val consultas = java.util.concurrent.ConcurrentHashMap<Triple<String,List<String>,String?>, Pair<Long,List<Pair<String,String>>>>()
    fun limparCache() = consultas.clear()

    suspend fun fontes(tipo: String, ids: List<String>, dono: String? = null): List<Pair<String,String>> =
        withContext(Dispatchers.IO) {
            val chave = Triple(tipo,ids,dono)
            val agora = System.nanoTime()
            consultas[chave]?.takeIf { agora - it.first < TimeUnit.MINUTES.toNanos(5) }?.let { return@withContext it.second }
            instalados().map { a -> async {
                ids.distinct().filter { (publico(it) || dono == a.chave) && a.aceita("stream", tipo, it) }
                    .flatMap { id -> ler(a.endpoint("stream", tipo, id))?.optJSONArray("streams").objetos().mapNotNull { s ->
                        val url = s.optString("url").takeIf { it.toHttpUrlOrNull() != null } ?: return@mapNotNull null
                        Resolvedor404.fonteImportada(a.nome, url, s)
                    } }
            } }.awaitAll().flatten().distinctBy { it.second }.also {
                if (consultas.size > 256) consultas.clear()
                consultas[chave] = agora to it
            }
        }

    private val previews = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, JSONObject>(512,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?) = size > 512
    })
    suspend fun meta(ref: Ref): JSONObject? = withContext(Dispatchers.IO) {
        val ativos = instalados()
        if (ativos.none { it.chave == ref.addon }) return@withContext null
        for (a in ativos.sortedByDescending { it.chave == ref.addon }) {
            if ((!publico(ref.id) && a.chave != ref.addon) || !a.aceita("meta", ref.tipo, ref.id)) continue
            val m = ler(a.endpoint("meta",ref.tipo,ref.id))?.optJSONObject("meta")
            if (m?.optString("id") == ref.id && m.optString("type") == ref.tipo) {
                previews[ref.chave] = m
                return@withContext m
            }
        }
        previews.get(ref.chave)
    }

    data class Catalogo(val addon: Addon, val dados: JSONObject) {
        val tipo = dados.getString("type")
        val id = dados.getString("id")
        val nome = addon.nome + " · " + dados.optString("name",id) + " · " + when (tipo) { "movie" -> "Filmes"; "series" -> "Séries"; else -> "Canais" }
        private val extras = dados.optJSONArray("extra").objetos()
        fun suporta(nome: String) = extras.any { it.optString("name") == nome } ||
            dados.optJSONArray("extraSupported")?.textos()?.contains(nome) == true
        fun disponivel(busca: Boolean): Boolean {
            val permitidos = if (busca) setOf("search","skip") else setOf("skip")
            return (!busca || suporta("search")) && extras.none { it.optBoolean("isRequired") && it.optString("name") !in permitidos } &&
                dados.optJSONArray("extraRequired")?.textos().orEmpty().all { it in permitidos }
        }
        suspend fun pagina(busca: String?, skip: Int): List<JSONObject> = withContext(Dispatchers.IO) {
            val extras = listOfNotNull(busca?.let { "search=" + java.net.URLEncoder.encode(it,"UTF-8").replace("+","%20") },
                skip.takeIf { it > 0 || this@Catalogo.extras.any { e -> e.optString("name") == "skip" && e.optBoolean("isRequired") } }?.let { "skip=$it" }).joinToString("&").ifEmpty { null }
            ler(addon.endpoint("catalog",tipo,id,extras))?.optJSONArray("metas").objetos()
                .filter { it.optString("id").isNotBlank() && it.optString("name").isNotBlank() && it.optString("type") == tipo }
        }
    }

    suspend fun filas(ctx: androidx.appcompat.app.AppCompatActivity, busca: String? = null, tipo: String? = null): List<Inicio.Fila> = coroutineScope {
        instalados().flatMap { a -> a.manifesto.optJSONArray("catalogs").objetos().mapNotNull {
            runCatching { Catalogo(a,it) }.getOrNull()
        } }.filter { (tipo == null || it.tipo == tipo) && it.tipo in setOf("movie","series","channel","tv") &&
            it.addon.aceita("catalog",it.tipo,it.id) && it.disponivel(busca != null && it.suporta("search")) }.map { cat -> async {
            val remota = busca != null && cat.suporta("search")
            val primeira = cat.pagina(busca.takeIf { remota },0)
            val page = if (busca == null || remota) primeira else {
                val prefixo = "stremio/${cat.addon.chave}/${cat.tipo}/"
                val vistos = synchronized(previews) { previews.filterKeys { it.startsWith(prefixo) }.values.toList() }
                val termo = Vod.normalizar(busca)
                (primeira + vistos).filter { m ->
                    val nomes = listOf(m.optString("name"),m.optString("originalName")) +
                        m.optJSONArray("aliases")?.textos().orEmpty()
                    nomes.any { Vod.normalizar(it).contains(termo) }
                }
            }
            val cards = page.distinctBy { it.getString("id") }.map { cartao(ctx,cat,it) }.toMutableList()
            if (cards.isNotEmpty() && cat.suporta("skip") && (busca == null || remota)) cards += Inicio.Cartao("Ver mais","","+") {
                AddonMenu.proxima(ctx,cat,busca,page.size)
            }
            if (cards.isEmpty()) null else Inicio.Fila(cat.nome,cards)
        } }.awaitAll().filterNotNull()
    }

    suspend fun fontesDoVideo(ref: Ref, ep: Titulos.Ep?, publicos: List<String> = ref.idsPublicos): Map<String,List<String>> {
        val ids = if (ep == null) publicos else listOfNotNull(ep.videoId) + publicos.filter(::publico)
            .map { "$it:${ep.temporada}:${ep.numero}" }
        return coroutineScope {
            val principais = async { fontes(ref.tipo,ids.distinct(),ref.addon) }
            val equivalentes = if (ep == null || !publico(ref.id)) emptyList() else instalados()
                .filter { a -> a.chave != ref.addon && a.recursos.any { it.optString("name") == "stream" } }
                .map { a -> async(Dispatchers.IO) {
                    val id = publicos.firstOrNull { a.aceita("meta",ref.tipo,it) } ?: return@async emptyList()
                    val meta = ler(a.endpoint("meta",ref.tipo,id))?.optJSONObject("meta")
                    if (meta?.optString("id") != id || meta.optString("type") != ref.tipo) return@async emptyList()
                    val video = meta.optJSONArray("videos").objetos().singleOrNull {
                        it.optInt("season",-1) == ep.temporada && it.optInt("episode",-1) == ep.numero
                    }?.optString("id")?.takeIf(String::isNotBlank) ?: return@async emptyList()
                    fontes(ref.tipo,listOf(video),a.chave)
                } }.awaitAll().flatten()
            (principais.await() + equivalentes).distinctBy { it.second }.groupBy({ it.first },{ it.second })
        }
    }

    fun cartao(ctx: Context, c: Catalogo, m: JSONObject): Inicio.Cartao {
        val ref = Ref(c.addon.chave,c.tipo,m.getString("id"))
        previews.put(ref.chave,m)
        val nome = m.getString("name")
        val alvo = Alvo(nome,c.tipo == "series","",m.optString("releaseInfo").take(4),ref.chave,
            ref.id.removePrefix("tmdb:").takeIf { ref.id.startsWith("tmdb:") }?.toIntOrNull() ?: 0)
        return Inicio.Cartao(nome,m.optString("poster"),nome.take(1),alvo=alvo,sinopse=m.optString("description")) { FichaActivity.abrir(ctx,alvo) }
    }
}
