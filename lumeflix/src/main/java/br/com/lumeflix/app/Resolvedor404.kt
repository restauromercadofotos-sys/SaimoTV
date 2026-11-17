package br.com.lumeflix.app

// ============================================================
//  EXTRACTOR 404 - RESOLVEDOR SOB DEMANDA NO APP v2.0
//  Criado por 404 - assinatura permanente, nao remover
// ============================================================
// v2.0: FrostStream e Hollymovies no B64 (entram no fan-out
// sozinhas); buscarPor resolve o mestre HLS da Hollymovies;
// cadeia RedeFlix (uso unico) entra no FIM da fila e NUNCA
// vai ao cache.
// ============================================================
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@UnstableApi
object Resolvedor404 {

    @Volatile private var bestcineBase: String? = null

    fun iniciarBestcine(context: android.content.Context) {
        bestcineBase = context.getSharedPreferences("bestcine", 0).getString("base", null)
    }

    fun bestcineConfigurado() = bestcineBase != null

    fun configurarBestcine(context: android.content.Context, manifest: String) {
        val texto = manifest.trim()
        if (texto.isEmpty()) {
            bestcineBase = null
            context.getSharedPreferences("bestcine", 0).edit().clear().apply()
        } else {
            val uri = java.net.URI(texto)
            require(uri.scheme == "https" && uri.host == "bestcine.dpdns.org" &&
                uri.path.endsWith("/manifest.json") && uri.rawQuery == null &&
                uri.rawFragment == null && uri.userInfo == null)
            bestcineBase = texto.removeSuffix("/manifest.json")
            context.getSharedPreferences("bestcine", 0).edit().putString("base", bestcineBase).apply()
        }
        cache.clear()
    }

    suspend fun testarBestcine(): String = withContext(Dispatchers.IO) {
        val base = bestcineBase ?: return@withContext "Não configurado"
        try {
            http.newCall(Request.Builder().url("$base/manifest.json").build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) "Acesso negado (HTTP " + r.code + ")"
                else if (!r.isSuccessful) "Erro HTTP " + r.code
                else if (JSONObject(r.body?.string().orEmpty()).optString("id") == "com.bestcine.multisource")
                    "Acesso confirmado"
                else "Resposta inválida"
            }
        } catch (_: Exception) { "Falha de conexão ou resposta inválida" }
    }

    // == LISTA OFUSCADA 404 - gerada por ofuscar_404.py ==
    private const val CHAVE = 0x5A
    private val B64 = mapOf(
        "FenixFlix" to "Mi4uKilgdXU8PzQzIjw2MyJ0PD80MyIyLzh0NTQ2MzQ/",
        // 404: as 2 origens novas comuns
    )

    private fun decodificar(b64: String): String {
        return try {
            val bruto = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val limpo = bruto.map { ((it.toInt() and 0xFF) xor CHAVE).toByte() }.toByteArray()
            String(limpo, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private val bases: List<Pair<String, String>> get() { return B64.entries.mapNotNull {
            val url = decodificar(it.value)
            if (url.startsWith("http")) {
                val configurada = if (it.key == "FenixFlix")
                    "$url/qualities=4k,1080p,720p,sd%7Caudio=dublado,legendado%7Ccatalogs=none"
                else url
                it.key to configurada
            } else null
        } + listOfNotNull(bestcineBase?.let { "BestCine" to it })
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val cache = ConcurrentHashMap<String, Pair<Long, Map<String, List<String>>>>()
    private val imdbCache = ConcurrentHashMap<String, String>()
    /** URL final -> addon que a entregou, preservado para a interface do player. */
    private val origens = ConcurrentHashMap<String, String>()
    /** URL final -> qualidade anunciada pelo addon (ex.: 1080p, 720p, 4K). */
    private val qualidades = ConcurrentHashMap<String, String>()

    fun origem(url: String): String? = origens[url]
    fun qualidade(url: String): String? = qualidades[url]
    private const val TTL_MS = 5 * 60 * 1000L
    private const val TMDB_KEY = "15d2ea6d0dc1d476efbca3eba2b9bbfb"

    private fun obterImdbId(tmdbId: Int, serie: Boolean): String? {
        val chave = "${if (serie) "tv" else "movie"}:$tmdbId"
        imdbCache[chave]?.let { return it }
        return try {
            val endpoint = if (serie) "tv" else "movie"
            val req = Request.Builder()
                .url("https://api.themoviedb.org/3/$endpoint/$tmdbId/external_ids?api_key=$TMDB_KEY")
                .header("Accept", "application/json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val imdb = json.optString("imdb_id").takeIf { it.isNotBlank() }
                    if (imdb != null) imdbCache[chave] = imdb
                    imdb
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Fontes 404 de um titulo. Filme: so tmdbId. Serie: informe temporada e numero. */
    suspend fun fontes(
        tmdbId: Int,
        serie: Boolean,
        temporada: Int = 0,
        numero: Int = 0,
        incluirFenix: Boolean = true,
    ): Map<String, List<String>> {
        if (tmdbId <= 0) return emptyMap()
        val tipo = if (serie) "series" else "movie"
        val mid = if (serie) "tmdb:$tmdbId:$temporada:$numero" else "tmdb:$tmdbId"
        val chaveCache = "$tipo:$mid:" + if (incluirFenix) "todos" else "sem-fenix"
        cache[chaveCache]?.let { (t, v) ->
            if (System.currentTimeMillis() - t < TTL_MS) return v
        }
        return withContext(Dispatchers.IO) {
            val res = coroutineScope {
                val (crus, cadeia) = coroutineScope {
                    // Provedores TMDB começam já. Só Frost/Fenix aguardam IMDb.
                    val imdbFut = async { obterImdbId(tmdbId, serie) }
                    val futsComuns = bases
                        .filter { (nome, _) -> incluirFenix || nome != "FenixFlix" }
                        .map { (nome, base) ->
                            async {
                                val precisaImdb = nome == "BestCine" || nome == "FenixFlix" || nome == "FrostStream" ||
                                    base.contains("froststream")
                                val imdbMid = if (precisaImdb) imdbFut.await()?.let {
                                    if (serie) "$it:$temporada:$numero" else it
                                } else null
                                buscarPor(nome, base, tipo, mid, imdbMid)
                            }
                        }
                    // 404: cadeia entra no FIM da fila - e NUNCA no cache (uso unico)
                    // Criado por 404 - assinatura permanente, nao remover
                    val futCadeia = async { emptyList<Pair<String, String>>() }
                    futsComuns.awaitAll().flatten() to futCadeia.await()
                }

                val vistos = mutableSetOf<String>()
                val fontes = linkedMapOf<String, MutableList<String>>()
                for ((versao, url) in crus) {
                    if (vistos.add(url)) fontes.getOrPut(versao) { mutableListOf() }.add(url)
                }
                val mapa: Map<String, List<String>> = fontes.mapValues { it.value.toList() }

                val cadeiaUrls = mutableSetOf<String>()
                val final: Map<String, List<String>> = if (cadeia.isNotEmpty()) {
                    val comCadeia = LinkedHashMap<String, MutableList<String>>()
                    mapa.forEach { (v, urls) -> comCadeia[v] = urls.toMutableList() }
                    for ((versao, url) in cadeia) {
                        cadeiaUrls.add(url)
                        origens.putIfAbsent(url, "RedeFlix")
                        val list = comCadeia.getOrPut(versao) { mutableListOf() }
                        if (url !in list) list.add(url)
                    }
                    comCadeia
                } else mapa

                if (mapa.isNotEmpty()) {
                    cache[chaveCache] = System.currentTimeMillis() to final.filterValues { urls ->
                        urls.none { it in cadeiaUrls }
                    }.filterValues { it.isNotEmpty() }
                }
                final
            }
            res ?: emptyMap()
        }
    }

    /**
     * Consulta o FenixFlix isoladamente para o resultado aparecer sem esperar
     * os demais addons. IMDb é preferido; TMDB é tentado quando ele volta vazio.
     */
    suspend fun fontesFenix(
        tmdbId: Int,
        serie: Boolean,
        temporada: Int = 0,
        numero: Int = 0,
    ): Map<String, List<String>> = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext emptyMap()
        val tipo = if (serie) "series" else "movie"
        val tmdbMid = if (serie) "tmdb:$tmdbId:$temporada:$numero" else "tmdb:$tmdbId"
        val base = bases.firstOrNull { it.first == "FenixFlix" }?.second
            ?: return@withContext emptyMap()
        // O FenixFlix aceita TMDB diretamente; evita uma chamada extra antes
        // da primeira tentativa. IMDb fica como reserva quando TMDB volta vazio.
        var crus = buscarPor("FenixFlix", base, tipo, tmdbMid, null)
        var idUsado = tmdbMid
        if (crus.isEmpty()) {
            val imdbId = obterImdbId(tmdbId, serie)
            val imdbMid = imdbId?.let {
                if (serie) "$it:$temporada:$numero" else it
            }
            if (imdbMid != null) {
                crus = buscarPor("FenixFlix", base, tipo, tmdbMid, imdbMid)
                idUsado = imdbMid
            }
        }
        val vistos = mutableSetOf<String>()
        val saida = linkedMapOf<String, MutableList<String>>()
        for ((versao, url) in crus) {
            if (vistos.add(url)) saida.getOrPut(versao) { mutableListOf() }.add(url)
        }
        Log.i("SaimoFenix", "$tipo tmdb=$tmdbId id=$idUsado fontes=${vistos.size}")
        saida.mapValues { it.value.toList() }
    }

    private fun buscar(base: String, tipo: String, mid: String): List<Pair<String, String>> {
        val agent = if (base.contains("froststream")) "OnePlay-Matrix/3.3.9 Kodi" else "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        return try {
            val pedido = http.newCall(
                Request.Builder()
                    .url("${base.trimEnd('/')}/stream/$tipo/$mid.json")
                    .header("Accept", "application/json")
                    .header("User-Agent", agent)
                    .build()
            ).execute()
            pedido.use { r ->
                if (!r.isSuccessful) return emptyList()
                val corpo = r.body?.string() ?: return emptyList()
                val streams = JSONObject(corpo).optJSONArray("streams")
                    ?: return emptyList()
                val saida = mutableListOf<Pair<String, String>>()
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val url = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                    extrairQualidade(s)?.let { qualidades[url] = it }
                    saida.add(classificar(s) to url)
                }
                saida
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 404: buscas que exigem resolver antes de entregar (2 saltos)
    // Criado por 404 - assinatura permanente, nao remover
    private fun buscarPor(
        nome: String,
        base: String,
        tipo: String,
        mid: String,
        imdbMid: String? = null
    ): List<Pair<String, String>> {
        val idParaUsar = if (nome == "FenixFlix" || nome == "FrostStream" || base.contains("froststream")) {
            imdbMid ?: mid
        } else {
            mid
        }
        val resultado = if (nome == "Hollymovies") {
            val crus = buscar(base, tipo, idParaUsar)
            crus.flatMap { (versao, url) ->
                if (url.endsWith(".m3u8")) {
                    val melhor = melhorVariante(url, http)
                    listOf(versao to (melhor ?: url))
                } else listOf(versao to url)
            }
        } else buscar(base, tipo, idParaUsar)
        resultado.forEach { (_, url) ->
            // Se a mesma URL vier de vários addons, mantém FenixFlix visível.
            if (nome == "FenixFlix") origens[url] = nome
            else origens.putIfAbsent(url, nome)
        }
        return resultado
    }

    // 404: passo de variante HLS (escolhe a melhor qualidade)
    // Criado por 404 - assinatura permanente, nao remover
    fun melhorVariante(master: String, cliente: OkHttpClient): String? = try {
        cliente.newCall(Request.Builder().url(master).build()).execute().use { r ->
            if (!r.isSuccessful) null
            else {
                var melhor: Pair<Int, String>? = null
                var pendente = -1
                for (linha in (r.body?.string() ?: "").lines()) {
                    if (linha.startsWith("#EXT-X-STREAM-INF")) {
                        pendente = Regex("BANDWIDTH=(\\d+)").find(linha)
                            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    } else if (pendente >= 0 && linha.isNotBlank() && !linha.startsWith("#")) {
                        val url = if (linha.startsWith("http")) linha.trim()
                        else master.substringBeforeLast("/") + "/" + linha.trim()
                        if (melhor == null || pendente > melhor!!.first)
                            melhor = pendente to url
                        pendente = -1
                    }
                }
                melhor?.second
            }
        }
    } catch (e: Exception) { null }

    /** Qualidade anunciada no nome, título ou descrição do stream Stremio. */
    private fun extrairQualidade(s: JSONObject): String? {
        val texto = listOf(s.optString("name"), s.optString("title"), s.optString("description"))
            .joinToString(" ")
        Regex("""(?i)(?<!\d)(2160|1440|1080|720|576|480|360)\s*p?(?!\d)""")
            .find(texto)?.groupValues?.get(1)?.let { return "${it}p" }
        return when {
            Regex("""(?i)\b(4k|uhd)\b""").containsMatchIn(texto) -> "4K"
            Regex("""(?i)\b(fhd|full\s*hd)\b""").containsMatchIn(texto) -> "1080p"
            Regex("""(?i)\bhd\b""").containsMatchIn(texto) -> "720p"
            Regex("""(?i)\bsd\b""").containsMatchIn(texto) -> "480p"
            else -> null
        }
    }

    /** Dublado/Legendado deduzido do nome e do detalhe do stream. */
    private fun classificar(s: JSONObject): String {
        val texto = (s.optString("name") + " " + s.optString("title") +
                " " + s.optString("description")).lowercase()
        return if ("dub" in texto || "dual" in texto || "nacional" in texto || "português" in texto || "portugues" in texto || "pt-br" in texto) "dub" else "leg"
    }
}

// Assinatura 404 - permanente, nao remover.
