package br.com.lumeflix.app

import android.content.Context
import androidx.media3.common.util.UnstableApi

/**
 * De um título do acervo ao que dá para tocar.
 *
 * O filme mora no arquivo da letra; a série, no pedaço dela; anime e dorama,
 * nas coleções. Antes cada tela fazia esse caminho do seu jeito — e cada uma
 * errava num lugar diferente. Aqui é um só, usado pela ficha, pelo player e
 * pelo "Continue assistindo".
 */
@UnstableApi
object Titulos {

    /** Uma fonte tocável, com versão e provedor quando veio de um addon. */
    data class Opcao(
        val versao: String,
        val url: String,
        val origem: String? = Resolvedor404.origem(url),
        val qualidade: String? = Resolvedor404.qualidade(url),
    )

    /** Um episódio com todas as versões e fontes juntas. */
    data class Ep(val temporada: Int, val numero: Int, val fontes: Map<String, List<String>>, val videoId: String? = null) {
        val versoes: Set<String> get() = fontes.keys
    }

    data class Resolvido(
        val alvo: Alvo,
        /// O nome que vai na chave de progresso — o mesmo desde a 1.x.
        val nomeChave: String,
        val filme: Filme?,
        val episodios: List<Ep>,
        val tmdbId: Int,
        /// Título do Extras: não deixa rastro (progresso, "continuar", Google TV).
        val reservado: Boolean = false,
    ) {
        val serie: Boolean get() = filme == null
        val temporadas: List<Int> get() = episodios.map { it.temporada }.distinct().sorted()

        fun chave(ep: Ep?): String =
            if (ep == null) Progresso.chaveFilme(nomeChave)
            else Progresso.chaveEpisodio(nomeChave, ep.temporada, ep.numero)

        fun episodio(temporada: Int, numero: Int) =
            episodios.firstOrNull { it.temporada == temporada && it.numero == numero }

        fun seguinte(ep: Ep): Ep? = episodios.asSequence()
            .filter { it.temporada > ep.temporada || (it.temporada == ep.temporada && it.numero > ep.numero) }
            .minWithOrNull(compareBy({ it.temporada }, { it.numero }))

        fun anterior(ep: Ep): Ep? = episodios.asSequence()
            .filter { it.temporada < ep.temporada || (it.temporada == ep.temporada && it.numero < ep.numero) }
            .maxWithOrNull(compareBy({ it.temporada }, { it.numero }))

        val endereco: Progresso.Endereco
            get() = Progresso.Endereco(alvo.titulo, alvo.serie, alvo.letra, alvo.ano, alvo.colecao, tmdbId)
    }

    suspend fun resolver(context: Context, alvo: Alvo, aoLerLocais: (suspend (Resolvido) -> Unit)? = null): Resolvido? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { resolverNoCatalogo(context, alvo, aoLerLocais) }
    }

    private suspend fun resolverNoCatalogo(context: Context, alvo: Alvo, aoLerLocais: (suspend (Resolvido) -> Unit)?): Resolvido? {
        if (!Addons.colecaoPermitida(alvo.colecao)) return null
        Addons.ref(alvo)?.let { ref ->
            val videos = Addons.meta(ref)?.optJSONArray("videos").objetos()
            val eps = videos.mapNotNull {
                val id = it.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val t = it.optInt("season",-1)
                val e = it.optInt("episode",-1)
                if (t < 0 || e < 0) null else Ep(t,e,emptyMap(),id)
            }.sortedWith(compareBy({ it.temporada }, { it.numero }))
            val filme = if (alvo.serie) null else Filme(alvo.titulo,emptyMap())
            val tmdb = alvo.tmdbId.takeIf { it > 0 } ?: if (ref.tipo in setOf("movie","series"))
                Detalhes.tmdbDoImdb(ref.id,alvo.serie) ?: 0 else 0
            val local = tmdb.takeIf { it > 0 }?.let { Generos.titulo(it,alvo.serie) }
                ?.let { Vod.achar(context,it,alvo.serie) }?.takeIf { Generos.id(it.nomeCompleto,it.serie) == tmdb }
            val nativo = local?.let { resolverNoCatalogo(context,Alvo(it.titulo,it.serie,it.letra,it.ano,tmdbId=tmdb),null) }
            val unidos = (eps + nativo?.episodios.orEmpty()).groupBy { it.temporada to it.numero }.values.map { grupo ->
                grupo.first().copy(fontes=grupo.flatMap { it.fontes.entries }.groupBy { it.key }
                    .mapValues { (_, entradas) -> entradas.flatMap { it.value }.distinct() })
            }.sortedWith(compareBy({ it.temporada }, { it.numero }))
            return Resolvido(alvo,nativo?.nomeChave ?: ref.progresso,nativo?.filme ?: filme,unidos,tmdb)
        }
        if (alvo.colecao.isNotEmpty()) {
            val achado = Vod.colecao(context, alvo.colecao)
                .singleOrNull {
                    if (alvo.tmdbId > 0) it.tmdbId.toIntOrNull() == alvo.tmdbId
                    else it.titulo.equals(alvo.titulo,ignoreCase=true) && it.ano == alvo.ano
                } ?: return null
            val tmdb = alvo.tmdbId.takeIf { it > 0 } ?: achado.tmdbId.toIntOrNull()?.takeIf { it > 0 }
                ?: Detalhes.buscarId(achado.titulo, true, achado.ano) ?: 0
            return Resolvido(alvo, Serie(achado.titulo, achado.ano, -1, 0).nomeCompleto, null,
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { agrupar(achado.episodios) }, tmdb)
        }
        if (alvo.tmdbId <= 0) Generos.carregar(context)
        val achado = Vod.Achado(alvo.titulo, alvo.serie, alvo.letra, alvo.ano)
        val idLocal = Generos.id(alvo.nomeCompleto,alvo.serie)
        val aceitaLocais = alvo.tmdbId <= 0 || alvo.tmdbId == idLocal
        var tmdb = alvo.tmdbId.takeIf { it > 0 } ?: Generos.id(alvo.nomeCompleto, alvo.serie)
            ?: 0
        if (tmdb <= 0) {
            tmdb = Detalhes.buscarId(alvo.titulo, alvo.serie, alvo.ano) ?: 0
        }
        return if (alvo.serie) {
            val serie = if (aceitaLocais) Vod.serie(context, achado) else null
            val grafias = if (serie == null || idLocal == null) emptyList() else Vod.series(context,alvo.letra)
                .filter { it != serie && Generos.id(it.nomeCompleto,true) == idLocal }
            val epsLocais = (listOfNotNull(serie) + grafias).flatMap { Vod.episodios(context,alvo.letra,it) }
            val agrupados = agrupar(epsLocais)
            val nome = serie?.nomeCompleto ?: if (alvo.tmdbId > 0) "tmdb/series/$tmdb" else alvo.nomeCompleto

            if (tmdb > 0) {
                if (agrupados.isNotEmpty()) aoLerLocais?.invoke(Resolvido(alvo, nome, null, agrupados, tmdb))
                val temporadasTmdb = Detalhes.temporadasDaSerie(tmdb)
                if (temporadasTmdb.isNotEmpty()) {
                    val mapa = agrupados.associateBy { it.temporada to it.numero }.toMutableMap()
                    for (t in temporadasTmdb) {
                        for (n in 1..t.totalEpisodios) {
                            if ((t.temporada to n) !in mapa) {
                                mapa[t.temporada to n] = Ep(t.temporada, n, emptyMap())
                            }
                        }
                    }
                    val todosEps = mapa.values.sortedWith(compareBy({ it.temporada }, { it.numero }))
                    return Resolvido(alvo, nome, null, todosEps, tmdb)
                }
            }

            if (agrupados.isEmpty()) {
                // 404: serie no catalogo sem fonte OU lancamento sem episodios locais -> busca nos addons
                if (tmdb > 0) {
                    val extras = Resolvedor404.fontes(tmdb, serie = true, temporada = 1, numero = 1)
                    if (extras.isNotEmpty()) {
                        val ep404 = Ep(1, 1, extras)
                        return Resolvido(alvo, nome, null, listOf(ep404), tmdb)
                    }
                }
                return null
            }
            Resolvido(alvo, nome, null, agrupados, tmdb)
        } else {
            val filme = if (aceitaLocais) juntarGrafias(context, achado) else null
            if (filme != null) {
                // O catálogo já tem links: abre imediatamente. Os addons são
                // consultados pelo player apenas se as fontes locais falharem.
                return Resolvido(alvo, filme.titulo, filme, emptyList(), tmdb)
            }
            // 404: filme novo / lancamento que NAO esta no catalogo local de filmes!
            if (tmdb > 0) {
                val extras = Resolvedor404.fontes(tmdb, serie = false)
                if (extras.isNotEmpty()) {
                    return Resolvido(alvo, "tmdb/movie/$tmdb", Filme(alvo.titulo, extras), emptyList(), tmdb)
                }
            }
            if (!Unlock.unlocked) return null
            val reservado = Vod.filmes(context, alvo.letra, reservados = true)
                .firstOrNull { it.titulo == alvo.titulo } ?: return null
            Resolvido(alvo, reservado.titulo, reservado, emptyList(), 0, reservado = true)
        }
    }

    /**
     * O filme com as fontes de todas as grafias do mesmo título.
     *
     * O acervo junta listas de origens diferentes, e o mesmo filme aparece
     * duas vezes: "Backrooms Um Nao-Lugar (2026)" com um servidor só (que
     * estava desligado) e "Backrooms: Um Não-Lugar" com vários. Abrir a
     * primeira dava "título indisponível" com o filme ali do lado. Aqui as
     * duas viram uma — a pedida primeiro, as outras como reserva.
     */
    private suspend fun juntarGrafias(context: Context, achado: Vod.Achado): Filme? {
        val id = Generos.id(achado.nomeCompleto, false)
        val daLetra = Vod.filmes(context, achado.letra)
        val iguais = if (id == null) emptyList() else daLetra.filter { Generos.id(it.titulo,false) == id }
        val pedido = daLetra.firstOrNull { it.titulo == achado.titulo }
            ?: Vod.filme(context, achado)
        val todos = listOfNotNull(pedido) + iguais.filter { it.titulo != pedido?.titulo }
        if (todos.isEmpty()) return null
        val fontes = linkedMapOf<String, List<String>>()
        for (f in todos) for ((v, urls) in f.fontes) fontes[v] = (fontes[v].orEmpty() + urls).distinct()
        return Filme(pedido?.titulo ?: achado.titulo, fontes)
    }

    private fun agrupar(eps: List<Episodio>): List<Ep> =
        eps.groupBy { it.temporada to it.numero }
            .map { (chave, lista) ->
                val fontes = linkedMapOf<String, List<String>>()
                for (e in lista) fontes[e.versao] = (fontes[e.versao].orEmpty() + e.urls).distinct()
                Ep(chave.first, chave.second, fontes)
            }
            .sortedWith(compareBy({ it.temporada }, { it.numero }))

    /**
     * Todas as fontes, na ordem em que devem ser tentadas.
     *
     * A versão escolhida vem primeiro, com todas as suas fontes; as outras
     * versões ficam atrás, como último recurso — melhor legendado que nada.
     */
    fun ordem(fontes: Map<String, List<String>>, versao: String, soEssa: Boolean = false): List<Opcao> {
        val versoes = fontes.keys.sortedBy { if (it == versao) 0 else if (it == "dub") 1 else 2 }
        val out = mutableListOf<Opcao>()
        for (v in versoes) {
            if (soEssa && v != versao && out.isNotEmpty()) continue
            fontes[v].orEmpty().forEach { out += Opcao(v, it) }
        }
        return out.distinctBy { it.url }
    }

    /** Servidor (host sem "www.") de uma url de fonte, ou vazio. */
    fun servidor(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")?.lowercase().orEmpty()

    /**
     * Nome legível da fonte. Addons preservam o nome informado pelo resolvedor;
     * fontes antigas usam o primeiro trecho do host. IPs ficam sem nome para a
     * interface manter apenas a sequência "Fonte N".
     */
    fun identificadorFonte(opcao: Opcao): String? {
        opcao.origem?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val host = servidor(opcao.url)
        if (host.isBlank() || ':' in host ||
            host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return null
        return host.substringBefore('.').takeIf { nome ->
            nome.isNotBlank() && nome.any { it.isLetter() }
        }?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * Fonte do episódio novo que mais se parece com a que tocava: a primeira do
     * mesmo servidor, senão a de mesma posição, senão 0 (a ordem de hoje).
     * Só olha a versão (dublado/legendado) que o episódio vai tocar, que é a
     * primeira de [opcoes]; o resultado é um índice de [opcoes].
     */
    fun preferida(opcoes: List<Opcao>, servidor: String, posicao: Int): Int {
        val versao = opcoes.firstOrNull()?.versao ?: return 0
        val doGrupo = opcoes.indices.filter { opcoes[it].versao == versao }
        if (servidor.isNotEmpty()) {
            doGrupo.firstOrNull { servidor(opcoes[it].url) == servidor }?.let { return it }
        }
        return doGrupo.getOrNull(posicao) ?: 0
    }

    fun rotulo(versao: String) = when (versao) { "leg" -> "Legendado"; "dub" -> "Dublado"; "original" -> "Original"; else -> versao }

    /** Episódio por onde uma série deve começar ou continuar. */
    fun ondeContinuar(context: Context, r: Resolvido): Ep? {
        if (r.episodios.isEmpty()) return null
        val ultimo = Progresso.ultimoEpisodio(context, r.nomeChave)
            ?.let { (t, e) -> r.episodio(t, e) }
        if (ultimo != null) {
            val chave = r.chave(ultimo)
            val terminou = Progresso.visto(context, chave) && Progresso.posicao(context, chave) <= 0
            return if (terminou) r.seguinte(ultimo) ?: ultimo else ultimo
        }
        return r.episodios.first()
    }
}
