package br.com.lumeflix.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * A ficha de um título: sinopse, duração, classificação, gêneros e elenco.
 *
 * O catálogo publicado traz nome e endereço; o arquivo de fichas acrescenta o
 * id do TMDB, o pôster e os gêneros. É o bastante para desenhar a fileira, e
 * pouco demais para quem parou num cartaz e quer saber do que se trata antes
 * de gastar dois minutos abrindo o filme numa TV Box.
 *
 * O resto só existe no endereço do próprio título no TMDB, e é um pedido por
 * título aberto — não por título listado. Com o id vindo da ficha não há busca
 * nem desempate: pergunta-se direto pelo número certo.
 *
 * Os mesmos campos, com os mesmos nomes, são o que o celular, o Mac, o Windows
 * e o site mostram.
 */
object Detalhes {

    private const val CHAVE = "15d2ea6d0dc1d476efbca3eba2b9bbfb"
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGENS = "https://image.tmdb.org/t/p/"

    data class Pessoa(val id: Int, val nome: String, val papel: String, val foto: String?)

    data class Ficha(
        val titulo: String = "",
        val sinopse: String = "",
        val frase: String = "",
        /** Minutos: do filme, ou de um episódio da série. */
        val duracao: Int? = null,
        val classificacao: String? = null,
        val nota: Double = 0.0,
        val ano: String = "",
        val generos: List<String> = emptyList(),
        /** Quem dirigiu o filme, ou quem criou a série. */
        val assinatura: String = "",
        val roteiro: String = "",
        val produtora: String = "",
        val elenco: List<Pessoa> = emptyList(),
        val capa: String? = null,
        /** A imagem larga do título, para o fundo da ficha. */
        val fundo: String? = null,
        val arteLauncher: String? = null,
        /** Quantas temporadas a série tem, segundo o TMDB. */
        val temporadas: Int? = null,
        /** A logo do título (PNG transparente), em português quando há. */
        val logo: String? = null,
        val outrasLogos: List<String> = emptyList(),
    ) {
        val vazia: Boolean
            get() = sinopse.isBlank() && elenco.isEmpty() && generos.isEmpty() && duracao == null
    }

    /**
     * A logo do título: a em português, senão a em inglês, senão a sem
     * língua. w300 basta para a altura em que aparece no player.
     */
    private fun logosDe(json: JSONObject): List<String> {
        val logos = json.optJSONObject("images")?.optJSONArray("logos") ?: return emptyList()
        val lista = (0 until logos.length()).mapNotNull { logos.optJSONObject(it) }
        return listOf("pt", "en", "").flatMap { idioma ->
            lista.filter { it.optString("iso_639_1").removeSuffix("null") == idioma }
        }.mapNotNull { it.optString("file_path").takeIf { caminho -> caminho.startsWith("/") } }
            .distinct().take(4).map { IMAGENS + "w500" + it }
    }

    data class TemporadaResumo(val temporada: Int, val totalEpisodios: Int)

    private val guardadas = recentes<String, Ficha>(150)
    private val resumoTemporadas = recentes<Int, List<TemporadaResumo>>(60)

    fun liberar() {
        synchronized(guardadas) { guardadas.clear() }
        synchronized(perfis) { perfis.clear() }
        synchronized(temporadas) { temporadas.clear() }
        synchronized(imagensDeEpisodios) { imagensDeEpisodios.clear() }
        synchronized(resumoTemporadas) { resumoTemporadas.clear() }
    }

    /**
     * A ficha de um título do acervo, ou nula quando o TMDB não a conhece.
     *
     * [idConhecido] vem das coleções de anime e dorama, que publicam o id do
     * TMDB no próprio arquivo — e acertam onde o arquivo de fichas erra: o
     * anime "Kevin" casava ali com outra série de mesmo nome.
     */
    suspend fun de(titulo: String, serie: Boolean, idConhecido: Int? = null): Ficha? =
        withContext(Dispatchers.IO) {
        val marca = (if (serie) "s:" else "f:") + titulo + (idConhecido?.let { "#$it" } ?: "")
        synchronized(guardadas) { guardadas[marca] }?.let { return@withContext it }
        val id = idConhecido?.takeIf { it > 0 } ?: Generos.id(titulo, serie)
            ?: buscarId(titulo, serie)
            ?: return@withContext null
        val ficha = baixar(id, serie) ?: return@withContext null
        synchronized(guardadas) { guardadas[marca] = ficha }
        ficha
        }

    private fun baixar(id: Int, serie: Boolean): Ficha? {
        val tipo = if (serie) "tv" else "movie"
        val extras = if (serie) "credits,content_ratings,images" else "credits,release_dates,images"
        // Só as logos interessam das imagens, e só nas línguas que a tela usa.
        val json = pedir("$BASE/$tipo/$id?api_key=$CHAVE&language=pt-BR&append_to_response=$extras" +
            "&include_image_language=pt,en,null")
            ?: return null

        val creditos = json.optJSONObject("credits")
        val equipe = creditos?.optJSONArray("crew")
        val direcao = mutableListOf<String>()
        val roteiro = mutableListOf<String>()
        if (equipe != null) {
            for (i in 0 until equipe.length()) {
                val pessoa = equipe.optJSONObject(i) ?: continue
                when (pessoa.optString("job")) {
                    "Director" -> direcao += pessoa.optString("name")
                    "Screenplay", "Writer", "Story" -> roteiro += pessoa.optString("name")
                }
            }
        }
        // Série não tem diretor único: quem a assina é quem a criou.
        val criadores = json.optJSONArray("created_by")?.let { lista ->
            (0 until lista.length()).mapNotNull { lista.optJSONObject(it)?.optString("name") }
        }.orEmpty()
        val assinatura = (if (direcao.isEmpty()) criadores else direcao).take(2).joinToString(", ")

        val elenco = creditos?.optJSONArray("cast")?.let { lista ->
            (0 until minOf(lista.length(), 20)).mapNotNull { i ->
                val pessoa = lista.optJSONObject(i) ?: return@mapNotNull null
                Pessoa(
                    id = pessoa.optInt("id"),
                    nome = pessoa.optString("name"),
                    papel = pessoa.optString("character"),
                    foto = pessoa.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }
                        ?.let { IMAGENS + "w185" + it },
                )
            }
        }.orEmpty()

        val data = json.optString(if (serie) "first_air_date" else "release_date")
        val duracao = if (serie) {
            json.optJSONArray("episode_run_time")?.takeIf { it.length() > 0 }?.optInt(0)
        } else {
            json.optInt("runtime").takeIf { it > 0 }
        }

        if (serie) {
            val seasonsArray = json.optJSONArray("seasons")
            if (seasonsArray != null) {
                val res = (0 until seasonsArray.length()).mapNotNull { i ->
                    val s = seasonsArray.optJSONObject(i) ?: return@mapNotNull null
                    val num = s.optInt("season_number")
                    val count = s.optInt("episode_count")
                    if (num > 0 && count > 0) TemporadaResumo(num, count) else null
                }
                if (res.isNotEmpty()) {
                    synchronized(resumoTemporadas) { resumoTemporadas[id] = res }
                }
            }
        }

        val logos = logosDe(json)
        return Ficha(
            titulo = json.optString(if (serie) "name" else "title"),
            sinopse = json.optString("overview"),
            frase = json.optString("tagline"),
            duracao = duracao?.takeIf { it > 0 },
            classificacao = classificacaoBR(json, serie),
            nota = json.optDouble("vote_average", 0.0),
            ano = data.take(4),
            generos = json.optJSONArray("genres")?.let { lista ->
                (0 until lista.length()).mapNotNull { lista.optJSONObject(it)?.optString("name") }
                    .map { when (it) {
                        "Sci-Fi & Fantasy" -> "Ficção científica e fantasia"
                        "Action & Adventure" -> "Ação e aventura"
                        else -> it
                    } }
            }.orEmpty(),
            assinatura = assinatura,
            roteiro = roteiro.distinct().take(2).joinToString(", "),
            produtora = json.optJSONArray("production_companies")
                ?.optJSONObject(0)?.optString("name").orEmpty(),
            elenco = elenco,
            capa = json.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                ?.let { IMAGENS + "w342" + it },
            // w780 e não original: o fundo fica escurecido e desfocado pelo
            // degradê, e um TV Box de 1 GB não precisa decodificar 4K para isso.
            fundo = json.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                ?.let { IMAGENS + "w780" + it },
            arteLauncher = json.optJSONObject("images")?.optJSONArray("backdrops")?.let { imagens ->
                sequenceOf("pt", "en").firstNotNullOfOrNull { idioma ->
                    (0 until imagens.length()).asSequence().mapNotNull { imagens.optJSONObject(it) }
                        .firstOrNull { it.optString("iso_639_1") == idioma &&
                            it.optString("file_path").startsWith("/") && it.optDouble("aspect_ratio") in 1.75..1.81 }
                        ?.let { IMAGENS + "w780" + it.getString("file_path") }
                }
            },
            temporadas = if (serie) json.optInt("number_of_seasons").takeIf { it > 0 } else null,
            logo = logos.firstOrNull(),
            outrasLogos = logos.drop(1),
        )
    }

    /** Quem é a pessoa: foto grande, biografia, nascimento, de onde é. */
    data class Perfil(
        val nome: String,
        val foto: String?,
        val biografia: String,
        val nascimento: String,
        val falecimento: String,
        val local: String,
        val conhecidaPor: String,
    )

    private val perfis = recentes<Int, Perfil>(60)

    /**
     * O perfil de uma pessoa. A biografia em português é curta ou falta para
     * quase todo mundo que não é brasileiro, então sem ela vale a em inglês —
     * melhor que um espaço vazio embaixo da foto.
     */
    suspend fun perfil(id: Int): Perfil? = withContext(Dispatchers.IO) {
        synchronized(perfis) { perfis[id] }?.let { return@withContext it }
        val json = pedir("$BASE/person/$id?api_key=$CHAVE&language=pt-BR") ?: return@withContext null
        var biografia = json.optString("biography")
        if (biografia.isBlank()) {
            biografia = pedir("$BASE/person/$id?api_key=$CHAVE&language=en-US")
                ?.optString("biography").orEmpty()
        }
        val perfil = Perfil(
            nome = json.optString("name"),
            foto = json.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }
                ?.let { IMAGENS + "h632" + it },
            biografia = biografia,
            nascimento = json.optString("birthday").takeIf { it != "null" }.orEmpty(),
            falecimento = json.optString("deathday").takeIf { it != "null" }.orEmpty(),
            local = json.optString("place_of_birth").takeIf { it != "null" }.orEmpty(),
            conhecidaPor = when (json.optString("known_for_department")) {
                "Acting" -> "Atuação"
                "Directing" -> "Direção"
                "Writing" -> "Roteiro"
                "Production" -> "Produção"
                "Sound" -> "Música"
                "Camera" -> "Fotografia"
                else -> ""
            },
        )
        synchronized(perfis) { perfis[id] = perfil }
        perfil
    }

    /** Um trabalho da pessoa que existe no acervo, com a capa que o TMDB já mandou. */
    data class Trabalho(val achado: Vod.Achado, val capa: String?, val papel: String)

    /** Mantido para quem chamava: o índice agora é o da busca, sem cópia. */
    suspend fun aquecer(context: Context) {
        withContext(Dispatchers.IO) { runCatching { Vod.entradas(context) } }
    }

    /**
     * O que um ator fez **e que existe neste acervo**.
     *
     * A filmografia inteira do TMDB não serve de dentro do aplicativo: listar
     * oitenta títulos dos quais setenta não abrem é uma lista que frustra. O
     * cruzamento é pelo id do TMDB, que o arquivo de fichas já traz para cada
     * título daqui — nome igual não engana, refilmagem não vira o original.
     *
     * A capa vem da própria resposta do TMDB, em 185 pixels: é pequena, chega
     * rápido e existe para quase todo trabalho — a do arquivo de fichas falta
     * para boa parte deles.
     */
    suspend fun acervoDe(context: Context, ator: Int): List<Trabalho> =
        withContext(Dispatchers.IO) {
            val json = pedir("$BASE/person/$ator/combined_credits?api_key=$CHAVE&language=pt-BR")
                ?: return@withContext emptyList()
            val trabalhos = mutableListOf<JSONObject>()
            for (campo in listOf("cast", "crew")) {
                val lista = json.optJSONArray(campo) ?: continue
                for (i in 0 until lista.length()) lista.optJSONObject(i)?.let { trabalhos += it }
            }
            // Ordem de popularidade: o que a pessoa é mais conhecida por fazer
            // vem primeiro, e não a ordem em que o TMDB devolveu.
            trabalhos.sortByDescending { it.optDouble("popularity", 0.0) }

            val vistos = HashSet<String>()
            val saida = mutableListOf<Trabalho>()
            for (trabalho in trabalhos) {
                val idDoTitulo = trabalho.optInt("id").takeIf { it > 0 } ?: continue
                val serie = trabalho.optString("media_type") == "tv"
                val titulo = Generos.titulo(idDoTitulo, serie) ?: continue
                val achado = Vod.achar(context, titulo, serie) ?: continue
                if (!vistos.add(achado.nomeCompleto + achado.serie)) continue
                val capa = trabalho.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { IMAGENS + "w185" + it }
                    ?: Generos.capa(achado.nomeCompleto, achado.serie)
                val papel = trabalho.optString("character").takeIf { it.isNotBlank() && it != "null" }
                    ?: trabalho.optString("job").takeIf { it.isNotBlank() && it != "null" }
                    ?: ""
                saida += Trabalho(achado, capa, papel)
            }
            saida
        }

    /** Um episódio como o TMDB descreve: nome, imagem, resumo, duração. */
    data class EpisodioTmdb(
        val numero: Int,
        val nome: String,
        val sinopse: String,
        val imagem: String?,
        val duracao: Int?,
    )

    private val temporadas = recentes<String, List<EpisodioTmdb>>(60)
    /** No máximo duas consultas extras ao TMDB simultâneas, pensando na Mi Box. */
    private val consultasDeImagens = Semaphore(2)
    private val imagensDeEpisodios = recentes<String, String>(180)

    /**
     * Consulta sob demanda os stills de um episódio sem miniatura em
     * /tv/{id}/season/{temporada}. O endpoint /images pode conter imagens
     * adicionais; só é chamado quando a fileira já recebeu os dados da temporada
     * e o cartão está visível. Nunca busca o acervo inteiro de uma vez.
     */
    suspend fun imagemEpisodio(id: Int, temporada: Int, numero: Int): String? =
        withContext(Dispatchers.IO) {
            if (id <= 0 || temporada < 0 || numero <= 0) return@withContext null
            val chave = "$id|$temporada|$numero"
            synchronized(imagensDeEpisodios) { imagensDeEpisodios[chave] }
                ?.let { return@withContext it }
            val json = consultasDeImagens.withPermit {
                pedir("$BASE/tv/$id/season/$temporada/episode/$numero/images?api_key=$CHAVE")
            } ?: return@withContext null
            val stills = json.optJSONArray("stills") ?: return@withContext null
            val caminho = (0 until stills.length()).firstNotNullOfOrNull { i ->
                stills.optJSONObject(i)?.optString("file_path")
                    ?.takeIf { it.isNotBlank() && it != "null" }
            } ?: return@withContext null
            val imagem = IMAGENS + "w300" + caminho
            synchronized(imagensDeEpisodios) { imagensDeEpisodios[chave] = imagem }
            imagem
        }

    /** Os episódios de uma temporada, para a lista da ficha ter nome e imagem. */
    /** Relaciona a numeração absoluta à temporada quando as duas sequências completas coincidem. */
    suspend fun dadosDaTemporada(id: Int, temporada: Int, locais: List<Titulos.Ep>): Map<Int, EpisodioTmdb> {
        val dados = Detalhes.temporada(id, temporada).associateBy { it.numero }
        if (dados.isEmpty() || temporada <= 1 || locais.size != dados.size) return dados
        val numeros = locais.map { it.numero }.sorted()
        if (numeros.first() <= 1 || numeros.first() in dados) return dados
        val resumo = temporadasDaSerie(id)
        val anteriores = resumo.filter { it.temporada in 1 until temporada }
        if (anteriores.size != temporada - 1) return dados
        val deslocamento = anteriores.sumOf { it.totalEpisodios }
        if (!numeros.withIndex().all { (i, n) -> n == deslocamento + i + 1 && i + 1 in dados }) return dados
        return dados.mapKeys { (numero, _) -> numero + deslocamento }
    }

    suspend fun temporada(id: Int, numero: Int): List<EpisodioTmdb> = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext emptyList()
        val chave = "$id|$numero"
        synchronized(temporadas) { temporadas[chave] }?.let { return@withContext it }
        val rota = "$BASE/tv/$id/season/$numero?api_key=$CHAVE"
        val lista = pedir("$rota&language=pt-BR")?.optJSONArray("episodes")?.takeIf { it.length() > 0 }
            ?: pedir(rota)?.optJSONArray("episodes") ?: return@withContext emptyList()
        val out = (0 until lista.length()).mapNotNull { i ->
            val ep = lista.optJSONObject(i) ?: return@mapNotNull null
            EpisodioTmdb(
                numero = ep.optInt("episode_number"),
                nome = ep.optString("name").takeIf { it != "null" }.orEmpty(),
                sinopse = ep.optString("overview").takeIf { it != "null" }.orEmpty(),
                // w300: a imagem ocupa um cartão pequeno na lista.
                imagem = ep.optString("still_path").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { IMAGENS + "w300" + it },
                duracao = ep.optInt("runtime").takeIf { it > 0 },
            )
        }
        if (out.isNotEmpty()) synchronized(temporadas) { temporadas[chave] = out }
        if (out.isNotEmpty() && out.none { it.imagem != null }) {
            Log.i("LumeFlix-TMDB", "Temporada $numero de série $id sem still_path; consultando /images só para cartões visíveis")
        }
        out
    }

    /**
     * "Mais como este": o que o TMDB recomenda **e existe no acervo**.
     *
     * Mesmo cruzamento da filmografia: pelo id, então só aparece o que abre.
     */
    suspend fun parecidos(context: Context, id: Int, serie: Boolean): List<Trabalho> =
        withContext(Dispatchers.IO) {
            if (id <= 0) return@withContext emptyList()
            Generos.carregar(context)
            val tipo = if (serie) "tv" else "movie"
            val vistos = HashSet<String>()
            val saida = mutableListOf<Trabalho>()
            for (rota in listOf("recommendations", "similar")) {
                val lista = pedir("$BASE/$tipo/$id/$rota?api_key=$CHAVE&language=pt-BR")
                    ?.optJSONArray("results") ?: continue
                for (i in 0 until lista.length()) {
                    val item = lista.optJSONObject(i) ?: continue
                    if (item.optInt("id") == id) continue
                    val titulo = Generos.titulo(item.optInt("id"), serie) ?: continue
                    val achado = Vod.achar(context, titulo, serie) ?: continue
                    if (!vistos.add(achado.nomeCompleto)) continue
                    val capa = item.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                        ?.let { IMAGENS + "w185" + it } ?: Generos.capa(achado.nomeCompleto, serie)
                    saida += Trabalho(achado, capa, "")
                    if (saida.size >= 20) return@withContext saida
                }
            }
            saida
        }

    /** Pessoas pelo nome, para a busca achar atores também. */
    suspend fun pessoas(termo: String): List<Pessoa> = withContext(Dispatchers.IO) {
        if (termo.trim().length < 3) return@withContext emptyList()
        val q = java.net.URLEncoder.encode(termo.trim(), "UTF-8")
        val lista = pedir("$BASE/search/person?api_key=$CHAVE&language=pt-BR&query=$q")
            ?.optJSONArray("results") ?: return@withContext emptyList()
        (0 until minOf(lista.length(), 10)).mapNotNull { i ->
            val p = lista.optJSONObject(i) ?: return@mapNotNull null
            if (p.optString("known_for_department") != "Acting") return@mapNotNull null
            Pessoa(p.optInt("id"), p.optString("name"), "",
                p.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { IMAGENS + "w185" + it })
        }
    }

    private fun classificacaoBR(json: JSONObject, serie: Boolean): String? {
        if (serie) {
            val lista = json.optJSONObject("content_ratings")?.optJSONArray("results")
                ?: return null
            for (i in 0 until lista.length()) {
                val item = lista.optJSONObject(i) ?: continue
                if (item.optString("iso_3166_1") == "BR") {
                    return item.optString("rating").takeIf { it.isNotBlank() }
                }
            }
            return null
        }
        val lista = json.optJSONObject("release_dates")?.optJSONArray("results") ?: return null
        for (i in 0 until lista.length()) {
            val item = lista.optJSONObject(i) ?: continue
            if (item.optString("iso_3166_1") != "BR") continue
            val datas = item.optJSONArray("release_dates") ?: return null
            for (j in 0 until datas.length()) {
                val nota = datas.optJSONObject(j)?.optString("certification")
                if (!nota.isNullOrBlank()) return nota
            }
        }
        return null
    }

    private lateinit var cliente: okhttp3.OkHttpClient
    fun iniciarCache(context: Context) {
        cliente = Playback.client.newBuilder()
            .cache(okhttp3.Cache(java.io.File(context.cacheDir, "metadados"), 12L * 1024 * 1024))
            .addNetworkInterceptor { chain ->
                val resposta = chain.proceed(chain.request())
                if (resposta.isSuccessful) resposta.newBuilder().header("Cache-Control", "private, max-age=21600").build() else resposta
            }.build()
    }

    private fun pedir(url: String): JSONObject? = runCatching {
        val pedido = Request.Builder().url(url)
            .header("User-Agent", Playback.DEFAULT_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        cliente.newCall(pedido).execute().use { r ->
            if (!r.isSuccessful) {
                // Registra somente a rota, jamais a API key dos parâmetros.
                Log.w("LumeFlix-TMDB", "HTTP ${r.code} em ${r.request.url.encodedPath}")
                null
            } else JSONObject(r.body.string())
        }
    }.onFailure {
        Log.w("LumeFlix-TMDB", "Falha ao consultar metadados ou miniatura", it)
    }.getOrNull()

    suspend fun temporadasDaSerie(tmdbId: Int): List<TemporadaResumo> = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext emptyList()
        synchronized(resumoTemporadas) { resumoTemporadas[tmdbId] }?.let { return@withContext it }
        val json = pedir("$BASE/tv/$tmdbId?api_key=$CHAVE&language=pt-BR") ?: return@withContext emptyList()
        val seasonsArray = json.optJSONArray("seasons") ?: return@withContext emptyList()
        val res = (0 until seasonsArray.length()).mapNotNull { i ->
            val s = seasonsArray.optJSONObject(i) ?: return@mapNotNull null
            val num = s.optInt("season_number")
            val count = s.optInt("episode_count")
            if (num > 0 && count > 0) TemporadaResumo(num, count) else null
        }
        if (res.isNotEmpty()) {
            synchronized(resumoTemporadas) { resumoTemporadas[tmdbId] = res }
        }
        res
    }

    suspend fun buscarId(titulo: String, serie: Boolean, ano: String = ""): Int? = withContext(Dispatchers.IO) {
        val tipo = if (serie) "tv" else "movie"
        val limpo = Generos.semAno(titulo).trim()
        if (limpo.isBlank()) return@withContext null
        val q = java.net.URLEncoder.encode(limpo, "UTF-8")
        val anoParam = if (ano.isNotBlank()) if (serie) "&first_air_date_year=$ano" else "&year=$ano" else ""
        val url = "$BASE/search/$tipo?api_key=$CHAVE&language=pt-BR&query=$q$anoParam"
        val json = pedir(url) ?: return@withContext null
        val results = json.optJSONArray("results") ?: return@withContext null
        if (results.length() == 0) return@withContext null
        val primeiro = results.optJSONObject(0) ?: return@withContext null
        primeiro.optInt("id").takeIf { it > 0 }
    }

    data class TituloTmdb(
        val tmdbId: Int,
        val titulo: String,
        val serie: Boolean,
        val ano: String,
        val capa: String?,
    )

    suspend fun fileirasEditoriais(context: Context, serie: Boolean): List<Pair<String, List<Vod.Achado>>> =
        withContext(Dispatchers.IO) {
            val tipo = if (serie) "tv" else "movie"
            val agora = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            val formato = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = agora.timeZone }
            val hoje = formato.format(agora.time)
            agora.add(java.util.Calendar.DAY_OF_YEAR, -90)
            val inicio = formato.format(agora.time)
            val cache = context.getSharedPreferences("fileiras_editoriais", Context.MODE_PRIVATE)
            val saida = mutableListOf<Pair<String, List<Vod.Achado>>>()

            suspend fun selecionar(ids: List<Int>, limite: Int): List<Vod.Achado> {
                val titulos = linkedSetOf<Vod.Achado>()
                for (id in ids.distinct()) {
                    val titulo = Generos.titulo(id, serie) ?: continue
                    Vod.achar(context, titulo, serie)?.let(titulos::add)
                    if (titulos.size == limite) break
                }
                return titulos.toList()
            }

            suspend fun incluir(nome: String, ids: List<Int>, limite: Int = 20) {
                val titulos = selecionar(ids, limite)
                if (titulos.isNotEmpty()) saida += nome to titulos
            }

            suspend fun consultar(nome: String, rota: String, parametros: String = "", limite: Int = 20) {
                val chave = "$tipo.$nome"
                val anteriores = cache.getString(chave, "").orEmpty().split(',').mapNotNull(String::toIntOrNull)
                val ids = if (cache.getString("$chave.data", "") == hoje) anteriores else {
                    val itens = pedir("$BASE/$rota?api_key=$CHAVE&language=pt-BR$parametros")?.optJSONArray("results")
                    if (itens == null) {
                        // Uma tendência antiga não deve aparecer como sendo de hoje.
                        if (rota.startsWith("trending/") || rota.endsWith("on_the_air")) emptyList() else anteriores
                    } else (0 until itens.length()).mapNotNull { itens.optJSONObject(it)?.optInt("id")?.takeIf { id -> id > 0 } }
                        .also { cache.edit().putString("$chave.data", hoje).putString(chave, it.joinToString(",")).apply() }
                }
                if (rota.startsWith("trending/") && ids.isNotEmpty()) {
                    val candidatos = ids.toMutableList()
                    var selecionados = selecionar(candidatos, limite)
                    for (pagina in 2..5) {
                        if (selecionados.size >= limite) break
                        val extra = "$chave.pagina$pagina"
                        val novos = if (cache.getString("$extra.data", "") == hoje) {
                            cache.getString(extra, "").orEmpty().split(',').mapNotNull(String::toIntOrNull)
                        } else {
                            val itens = pedir("$BASE/$rota?api_key=$CHAVE&language=pt-BR&page=$pagina")
                                ?.optJSONArray("results") ?: break
                            (0 until itens.length()).mapNotNull { itens.optJSONObject(it)?.optInt("id")?.takeIf { id -> id > 0 } }
                                .also { cache.edit().putString("$extra.data", hoje).putString(extra, it.joinToString(",")).apply() }
                        }
                        if (novos.isEmpty()) break
                        candidatos.addAll(novos)
                        selecionados = selecionar(candidatos, limite)
                    }
                    if (selecionados.isNotEmpty()) saida += nome to selecionados
                } else incluir(nome, ids, limite)
            }

            consultar("Em alta hoje", "trending/$tipo/day", limite = 10)
            if (serie) {
                consultar("Novas séries", "discover/tv", "&first_air_date.gte=$inicio&first_air_date.lte=$hoje&sort_by=popularity.desc")
                consultar("Séries no ar nesta semana", "tv/on_the_air", "&timezone=America%2FSao_Paulo")
            }
            val datas = if (serie) "first_air_date" else "primary_release_date"
            consultar("Lançamentos no streaming", "discover/$tipo",
                "&watch_region=BR&with_watch_monetization_types=flatrate&$datas.gte=$inicio&$datas.lte=$hoje&sort_by=popularity.desc")
            consultar(if (serie) "Séries mais bem avaliadas" else "Filmes mais bem avaliados", "$tipo/top_rated")
            if (!serie) {
                // Vencedores de melhor filme, cerimônias de 2011 a 2026 (oscars.org/oscars/ceremonies).
                incluir("Vencedores do Oscar de melhor filme", listOf(1054867, 1064213, 872585, 545611, 776503, 581734,
                    496243, 490132, 399055, 376867, 314365, 194662, 76203, 68734, 74643, 45269))
                // Os dez primeiros da votação de críticos Sight and Sound 2022 (BFI).
                incluir("Aclamados pela crítica", listOf(44012, 426, 15, 18148, 843, 62, 14626, 1018, 26317, 872))
            }
            saida
        }

    suspend fun buscarTitulos(termo: String): List<TituloTmdb> = withContext(Dispatchers.IO) {
        val limpo = termo.trim()
        if (limpo.length < 2) return@withContext emptyList()
        val q = java.net.URLEncoder.encode(limpo, "UTF-8")
        fun exato(item: JSONObject) = listOf("name", "title", "original_name", "original_title")
            .any { Vod.normalizar(item.optString(it)) == Vod.normalizar(limpo) }
        suspend fun consultar(idioma: String): List<JSONObject> {
            val results = pedir("$BASE/search/multi?api_key=$CHAVE&language=$idioma&query=$q&include_adult=false")
                ?.optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .filter { it.optString("media_type") in setOf("movie", "tv") }
        }
        val locais = consultar("pt-BR")
        val candidatos = if (locais.any(::exato)) locais else locais + consultar("en-US")
        val out = mutableListOf<TituloTmdb>()
        for (item in candidatos.distinctBy { it.optString("media_type") to it.optInt("id") }
            .sortedWith(compareByDescending<JSONObject>(::exato).thenByDescending { it.optDouble("popularity", 0.0) })) {
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            val isSerie = mediaType == "tv"
            val id = item.optInt("id").takeIf { it > 0 } ?: continue
            val tit = item.optString(if (isSerie) "name" else "title")
            if (tit.isBlank()) continue
            val data = item.optString(if (isSerie) "first_air_date" else "release_date")
            val poster = item.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                ?.let { IMAGENS + "w342" + it }
            out += TituloTmdb(id, tit, isSerie, data.take(4), poster)
        }
        out
    }
}
