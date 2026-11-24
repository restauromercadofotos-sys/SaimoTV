package br.com.lumeflix.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.transform.CircleCropTransformation
import coil3.request.placeholder
import coil3.request.crossfade
import coil3.request.transformations
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/**
 * O que abrir: um título do acervo, com o bastante para chegar até ele.
 *
 * A letra é onde o título mora no acervo comum; a coleção, quando não é
 * vazia, diz que ele mora na de animes ou na de doramas. O id do TMDB só vem
 * preenchido das coleções, que o publicam no próprio arquivo.
 */
data class Alvo(
    val titulo: String,
    val serie: Boolean,
    val letra: String,
    val ano: String = "",
    val colecao: String = "",
    val tmdbId: Int = 0,
) {
    /// O filme já traz o ano no nome ("O Fim da Rua (2026)"); a série o
    /// guarda à parte. Só se acrescenta quando o nome ainda não tem.
    val nomeCompleto: String get() =
        if (ano.isBlank() || titulo.trimEnd().endsWith("($ano)")) titulo else "$titulo ($ano)"

    fun em(intent: Intent): Intent = intent
        .putExtra(TITULO, titulo).putExtra(SERIE, serie).putExtra(LETRA, letra)
        .putExtra(ANO, ano).putExtra(COLECAO, colecao).putExtra(TMDB, tmdbId)

    companion object {
        private const val TITULO = "alvo.titulo"
        private const val SERIE = "alvo.serie"
        private const val LETRA = "alvo.letra"
        private const val ANO = "alvo.ano"
        private const val COLECAO = "alvo.colecao"
        private const val TMDB = "alvo.tmdb"

        fun de(intent: Intent?): Alvo? {
            val titulo = intent?.getStringExtra(TITULO) ?: return null
            if (!Addons.colecaoPermitida(intent.getStringExtra(COLECAO).orEmpty())) return null
            return Alvo(
                titulo = titulo,
                serie = intent.getBooleanExtra(SERIE, false),
                letra = intent.getStringExtra(LETRA).orEmpty(),
                ano = intent.getStringExtra(ANO).orEmpty(),
                colecao = intent.getStringExtra(COLECAO).orEmpty(),
                tmdbId = intent.getIntExtra(TMDB, 0).let { id ->
                    if (id == 51025 && intent.getBooleanExtra(SERIE,false) &&
                        titulo.equals("Paradise",true) && intent.getStringExtra(ANO) == "2025") 245927 else id
                },
            )
        }
    }
}

/**
 * A ficha de um título, como tela inteira.
 *
 * Antes era uma lista de linhas de texto — sinopse numa, elenco em outras,
 * sem foto nem capa. Agora é o que uma TV mostra: a imagem larga do filme ao
 * fundo, a capa, os números em selos, a sinopse, e o elenco em fotos redondas
 * logo abaixo do botão de assistir.
 *
 * "Assistir" não toca daqui: devolve o título para a tela do acervo, que é
 * quem sabe escolher fonte e temporada. Assim o caminho até o vídeo é um só,
 * venha a pessoa de onde vier.
 */
@androidx.media3.common.util.UnstableApi
class FichaActivity : TelaComMenu() {

    private lateinit var alvo: Alvo
    private lateinit var favoritar: TextView
    private lateinit var assistirBotao: TextView
    private var resolvido: Titulos.Resolvido? = null
    private var temporadaVista = 0
    private val episodios by lazy { EpisodiosAdapter(lifecycleScope) { ep -> tocarEpisodio(ep) } }
    private val parecidos by lazy {
        CapasAdapter(largura = (resources.displayMetrics.density * 140).toInt()) { achado ->
            abrir(this, Alvo(achado.titulo, achado.serie, achado.letra, achado.ano))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alvo = Alvo.de(intent) ?: run { finish(); return }
        setContentView(R.layout.activity_ficha)
        // A ficha é uma experiência de conteúdo em tela cheia. VOLTAR retorna
        // ao catálogo; manter a navegação fixa aqui espremia e cortava o herói.
        barraMenu?.visibility = View.GONE

        val assistir = findViewById<TextView>(R.id.fichaAssistir)
        assistirBotao = assistir
        favoritar = findViewById(R.id.fichaFavoritar)
        // O topo ocupa a primeira tela inteira; episódios, elenco e parecidos
        // aparecem rolando para baixo com o direcional.
        findViewById<View>(R.id.fichaTopo).minimumHeight =
            (resources.displayMetrics.density * if (BuildConfig.TV_AO_VIVO) 340 else 332).toInt()
        if (!BuildConfig.TV_AO_VIVO) {
            assistir.setBackgroundResource(R.drawable.acao_lumeflix)
            assistir.setTextColor(android.graphics.Color.BLACK)
            findViewById<View>(R.id.fichaCapaGrupo).visibility = View.GONE
            findViewById<LinearLayout>(R.id.fichaInfo).apply {
                val parametros = layoutParams as LinearLayout.LayoutParams
                parametros.marginStart = 0
                layoutParams = parametros
            }
            findViewById<TextView>(R.id.fichaTitulo).textSize = 32f
        }
        findViewById<View>(R.id.fichaVersao).setOnFocusChangeListener(crescerNoFoco)
        findViewById<RecyclerView>(R.id.fichaEpisodios).apply {
            layoutManager = LinearLayoutManager(this@FichaActivity, RecyclerView.HORIZONTAL, false)
            adapter = episodios
        }
        findViewById<RecyclerView>(R.id.fichaParecidos).apply {
            layoutManager = LinearLayoutManager(this@FichaActivity, RecyclerView.HORIZONTAL, false)
            parecidos.alturaDaCapa = (resources.displayMetrics.density * 210).toInt()
            adapter = parecidos
        }

        findViewById<TextView>(R.id.fichaTipo).text = getString(when (alvo.colecao) {
            "animes" -> R.string.vod_ficha_tipo_anime
            "doramas" -> R.string.vod_ficha_tipo_dorama
            else -> if (alvo.serie) R.string.vod_ficha_tipo_serie else R.string.vod_ficha_tipo_filme
        })
        // O ano vai no selo; no título ele só empurrava o nome para as
        // reticências — "Monstro: A História de Lizzie Borden (20…".
        findViewById<TextView>(R.id.fichaTitulo).text = Generos.semAno(alvo.titulo)
        findViewById<TextView>(R.id.fichaInicial).text = alvo.titulo.take(1).uppercase()

        // A capa do arquivo de fichas chega na hora; a da ficha do TMDB, se
        // vier diferente, substitui depois.
        if (Addons.ref(alvo) == null && (alvo.tmdbId == 0 || Generos.id(alvo.nomeCompleto,alvo.serie) == alvo.tmdbId)) Generos.capa(alvo.nomeCompleto, alvo.serie)?.let { mostrarCapa(it) }

        assistir.text = getString(textoDoAssistir())
        Icones.inicio(assistir, R.drawable.ic_play)
        assistir.setOnClickListener { assistir() }
        assistir.setOnFocusChangeListener(crescerNoFoco)
        favoritar.setOnFocusChangeListener(crescerNoFoco)
        // Anime e dorama moram fora do acervo comum e os favoritos guardam a
        // letra; sem letra não há como reabrir, então o botão não aparece.
        if (alvo.colecao.isNotEmpty()) {
            favoritar.visibility = View.GONE
        } else {
            atualizarFavorito()
            favoritar.setOnClickListener {
                VodFavoritos.alternar(this,
                    VodFavoritos.Item(alvo.titulo, alvo.serie, alvo.letra, alvo.ano))
                atualizarFavorito()
            }
        }

        favoritar.nextFocusRightId = R.id.fichaLerSinopse
        assistir.nextFocusLeftId = R.id.fichaAssistir
        // Descer dos botões cai nos episódios, quando houver; senão no elenco.
        for (id in listOf(R.id.fichaAssistir, R.id.fichaVersao, R.id.fichaFavoritar, R.id.fichaLerSinopse)) {
            findViewById<View>(id).nextFocusDownId = View.NO_ID
        }

        // Depois do primeiro layout: antes disso a rolagem toma o foco para si
        // e a tela abre sem nada focado — um controle que não responde.
        assistir.post { assistir.requestFocus() }
        carregar()
        resolver()
    }

    override fun onResume() {
        super.onResume()
        // Voltando do player, o botão e a lista mostram onde a pessoa parou.
        if (resolvido != null) { atualizarAssistir(); episodios.notifyDataSetChanged() }
    }

    /** Onde o título mora e o que tem para tocar: versões, temporadas, episódios. */
    private fun resolver() {
        lifecycleScope.launch(semDerrubar) {
            val r = Titulos.resolver(this@FichaActivity, alvo) { local ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    if (!isFinishing && !isDestroyed) {
                        resolvido = local
                        episodios.tmdbId = local.tmdbId
                        atualizarAssistir()
                        montarTemporadas(local)
                    }
                }
            } ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            val anterior = resolvido
            resolvido = r
            episodios.tmdbId = r.tmdbId
            atualizarAssistir()
            if (r.serie && anterior?.episodios != r.episodios) {
                montarTemporadas(r)
            }
            val versoes = r.filme?.fontes?.keys ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                r.episodios.flatMapTo(mutableSetOf<String>()) { it.versoes }
            }
            if (versoes.size > 1) {
                findViewById<TextView>(R.id.fichaVersao).apply {
                    visibility = View.VISIBLE
                    text = getString(R.string.ficha_versao, Titulos.rotulo(Preferencias.versao))
                    Icones.inicio(this, R.drawable.ic_translate)
                    setOnClickListener { escolherVersao(versoes) }
                }
            }
            carregarExtras(r.tmdbId)
        }
    }

    private fun atualizarAssistir() {
        val r = resolvido ?: return
        if (!r.serie) {
            assistirBotao.text = getString(textoDoAssistir())
            return
        }
        val ep = Titulos.ondeContinuar(this, r) ?: return
        val comecou = Progresso.ultimoEpisodio(this, r.nomeChave) != null
        assistirBotao.text = getString(if (comecou) R.string.ficha_continuar_ep else R.string.ficha_assistir_ep,
            ep.temporada, ep.numero)
        Icones.inicio(assistirBotao, R.drawable.ic_play)
    }

    private fun escolherVersao(versoes: Set<String>) {
        Painel.mostrar(this, getString(R.string.player_versao), versoes.sortedBy { if (it == "dub") 0 else 1 }.map { v ->
            Painel.Item(Titulos.rotulo(v), marcado = v == Preferencias.versao) {
                Preferencias.versao = v
                findViewById<TextView>(R.id.fichaVersao).text = getString(R.string.ficha_versao, Titulos.rotulo(v))
            }
        })
    }

    private fun montarTemporadas(r: Titulos.Resolvido) {
        val temporadas = r.temporadas
        if (temporadas.isEmpty()) return
        findViewById<View>(R.id.fichaEpisodiosBloco).visibility = View.VISIBLE
        val barra = findViewById<LinearLayout>(R.id.fichaTemporadas)
        barra.removeAllViews()
        val atual = temporadaVista.takeIf { it in temporadas } ?: Titulos.ondeContinuar(this, r)?.temporada ?: temporadas.first()
        temporadaVista = 0
        for (t in temporadas) {
            barra.addView(TextView(this).apply {
                id = View.generateViewId()
                if (t == atual) {
                    // Descer dos botões cai na temporada em que a pessoa está,
                    // não na que calhar de estar embaixo do botão.
                    for (b in listOf(R.id.fichaAssistir, R.id.fichaVersao, R.id.fichaFavoritar, R.id.fichaLerSinopse)) {
                        this@FichaActivity.findViewById<View>(b).nextFocusDownId = id
                    }
                }
                nextFocusDownId = R.id.fichaEpisodios
                text = getString(R.string.vod_temporada, t)
                tag = t
                textSize = 17f
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.texto_botao_ficha))
                setBackgroundResource(if (BuildConfig.TV_AO_VIVO) R.drawable.botao_player else R.drawable.temporada_lume)
                isFocusable = true
                val d = resources.displayMetrics.density
                setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() }
                // Focar a temporada já troca a lista: sem OK a mais.
                setOnFocusChangeListener { _, foco -> if (foco) mostrarTemporada(r, t) }
                setOnClickListener { mostrarTemporada(r, t) }
            })
        }
        // Nas pontas da fila de temporadas a seta para ali, como nas fileiras.
        barra.getChildAt(0)?.let { it.nextFocusLeftId = it.id }
        barra.getChildAt(barra.childCount - 1)?.let { it.nextFocusRightId = it.id }
        mostrarTemporada(r, atual)
    }

    private fun mostrarTemporada(r: Titulos.Resolvido, temporada: Int) {
        if (temporada == temporadaVista) return
        temporadaVista = temporada
        // Subir de um episódio volta à pílula desta temporada — e não à que
        // estiver em cima dele, que trocaria a lista sem ninguém pedir.
        val barra = findViewById<LinearLayout>(R.id.fichaTemporadas)
        episodios.acima = barra.findViewWithTag<View>(temporada)?.id ?: View.NO_ID
        for (i in 0 until barra.childCount) barra.getChildAt(i).isSelected = barra.getChildAt(i).tag == temporada
        val lista = r.episodios.filter { it.temporada == temporada }
        episodios.trocar(r, lista, emptyMap())
        val atual = Titulos.ondeContinuar(this, r)
        val indice = lista.indexOfFirst { it == atual }.coerceAtLeast(0)
        findViewById<RecyclerView>(R.id.fichaEpisodios).scrollToPosition(indice)
        lifecycleScope.launch(semDerrubar) {
            val info = Detalhes.dadosDaTemporada(r.tmdbId, temporada, lista)
            if (temporadaVista == temporada) episodios.completar(info)
        }
    }

    private fun tocarEpisodio(ep: Titulos.Ep) {
        PlayerActivity.abrir(this, alvo, ep.temporada, ep.numero)
    }

    /** "Mais como este": vem do TMDB depois que a tela já está de pé. */
    private var idExtras = 0
    private fun carregarExtras(id: Int) {
        if (id <= 0 || idExtras == id) return
        idExtras = id
        lifecycleScope.launch(semDerrubar) {
            val lista = Detalhes.parecidos(this@FichaActivity, id, alvo.serie)
            if (lista.isEmpty() || isFinishing) return@launch
            findViewById<View>(R.id.fichaParecidosBloco).visibility = View.VISIBLE
            parecidos.trocar(lista)
        }
    }

    private fun textoDoAssistir(): Int = when {
        alvo.serie -> R.string.vod_ficha_assistir_filme
        (Progresso.fracao(this, Progresso.chaveFilme(alvo.titulo)) ?: 0f) > 0f ->
            R.string.vod_ficha_continuar
        else -> R.string.vod_ficha_assistir_filme
    }

    private fun carregar() {
        val carregando = findViewById<ProgressBar>(R.id.fichaCarregando)
        carregando.visibility = View.GONE
        alvo.ano.takeIf { it.isNotBlank() }?.let { selo(findViewById(R.id.fichaSelos), it) }
        Addons.ref(alvo)?.let { ref ->
            lifecycleScope.launch(semDerrubar) {
                val m = Addons.meta(ref) ?: return@launch
                mostrar(Detalhes.Ficha(titulo=alvo.titulo,sinopse=m.optString("description"),ano=alvo.ano,
                    capa=m.optString("poster").ifBlank { null },fundo=m.optString("background").ifBlank { null },
                    logo=m.optString("logo").ifBlank { null }))
            }
            return
        }
        val arte = previa?.takeIf { it.first == alvo }?.second?.newDrawable(resources)
        previa = null
        if (arte != null && arte.intrinsicWidth >= arte.intrinsicHeight) {
            findViewById<ImageView>(R.id.fichaFundo).setImageDrawable(arte)
        } else {
            val capa = findViewById<ImageView>(R.id.fichaCapa)
            if (arte != null || Generos.capa(alvo.nomeCompleto, alvo.serie) != null) {
                findViewById<View>(R.id.fichaCapaGrupo).visibility = View.VISIBLE
                capa.scaleType = ImageView.ScaleType.FIT_CENTER
                if (arte != null) capa.setImageDrawable(arte)
            }
        }
        lifecycleScope.launch(semDerrubar) {
            // Sem as fichas em memória, o id do título não é conhecido. O
            // arquivo já está em disco na maioria das vezes: é instantâneo.
            val idInicial = alvo.tmdbId.takeIf { it > 0 }
            val salva = Detalhes.salva(alvo.titulo, alvo.serie, idInicial)
            if (salva != null) mostrar(salva)
            Generos.carregar(this@FichaActivity)
            if (Addons.ref(alvo) == null && (alvo.tmdbId == 0 || Generos.id(alvo.nomeCompleto,alvo.serie) == alvo.tmdbId)) Generos.capa(alvo.nomeCompleto, alvo.serie)?.let { mostrarCapa(it) }
            // Destaque de anime ou dorama chega sem o id: a coleção o publica,
            // e é ele que acerta — o arquivo de fichas casa "Kevin" com outra
            // série de mesmo nome.
            val id = alvo.tmdbId.takeIf { it > 0 }
                ?: alvo.colecao.takeIf { it.isNotEmpty() }?.let { tipo ->
                    runCatching {
                        Vod.colecao(this@FichaActivity, tipo)
                            .singleOrNull { it.titulo.equals(alvo.titulo, ignoreCase = true) && it.ano == alvo.ano }
                            ?.tmdbId?.toIntOrNull()
                    }.getOrNull()
                }
            carregarExtras(id ?: Generos.id(alvo.nomeCompleto, alvo.serie) ?: 0)
            val ficha = Detalhes.de(alvo.nomeCompleto, alvo.serie, id)
            if (alvo.serie) {
                // O TMDB nem sempre possui still_path para cada episódio.
                // Mesmo sem detalhes, mostra arte da série em vez de números.
                episodios.atualizarReserva(
                    ficha?.fundo?.replace("/w780/", "/w300/")
                        ?: ficha?.capa
                        ?: Generos.capa(alvo.nomeCompleto, true)
                )
            }
            carregando.visibility = View.GONE
            if (isFinishing || isDestroyed) return@launch
            if (ficha == null || ficha.vazia) {
                findViewById<TextView>(R.id.fichaTitulo).visibility = View.VISIBLE
                if (salva == null) findViewById<TextView>(R.id.fichaSinopse).text = getString(R.string.vod_ficha_vazia)
                return@launch
            }
            if (ficha != salva) mostrar(ficha)
        }
    }

    private fun mostrar(ficha: Detalhes.Ficha) {
        val nome = findViewById<TextView>(R.id.fichaTitulo)
        nome.text = ficha.titulo.ifBlank { Generos.semAno(alvo.titulo) }
        findViewById<ImageView>(R.id.fichaLogoTitulo).mostrarLogo(ficha, nome)
        ficha.fundo?.let { endereco ->
            findViewById<ImageView>(R.id.fichaFundo).load(endereco) {
                placeholder(findViewById<ImageView>(R.id.fichaFundo).drawable)
                listener(onSuccess = { _, _ -> findViewById<View>(R.id.fichaCapaGrupo).visibility = View.GONE })
            }
        }
        ficha.capa?.let { mostrarCapa(it) }

        findViewById<TextView>(R.id.fichaFrase).apply {
            text = ficha.frase
            visibility = if (ficha.frase.isBlank()) View.GONE else View.VISIBLE
        }

        val selos = findViewById<LinearLayout>(R.id.fichaSelos)
        selos.removeAllViews()
        if (ficha.nota > 0) selo(selos, "%.1f".format(ficha.nota), 0xFFFFD166.toInt(), R.drawable.ic_star)
        if (ficha.ano.isNotBlank()) selo(selos, ficha.ano)
        ficha.duracao?.let {
            selo(selos, if (alvo.serie) getString(R.string.vod_ficha_minutos_ep, it)
                        else duracaoLegivel(it))
        }
        ficha.temporadas?.let {
            selo(selos, resources.getQuantityString(R.plurals.vod_ficha_temporadas_n, it, it))
        }
        ficha.classificacao?.takeIf { it.isNotBlank() }?.let {
            selo(selos, it, corDaClassificacao(it))
        }

        findViewById<TextView>(R.id.fichaGeneros).apply {
            text = ficha.generos.joinToString("  ·  ")
            visibility = if (ficha.generos.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.fichaSinopse).text = ficha.sinopse
        if (!BuildConfig.TV_AO_VIVO) {
            findViewById<TextView>(R.id.fichaLerSinopse).apply {
                val resumo = findViewById<TextView>(R.id.fichaSinopse)
                resumo.post {
                    val layout = resumo.layout
                    visibility = if (layout != null && layout.lineCount > 0 &&
                        layout.getEllipsisCount(layout.lineCount - 1) > 0) View.VISIBLE else View.GONE
                }
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@FichaActivity)
                        .setTitle(ficha.titulo).setMessage(ficha.sinopse)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }

    }

    private fun mostrarCapa(endereco: String) {
        val capa = findViewById<ImageView>(R.id.fichaCapa)
        capa.load(endereco) {
            crossfade(200)
            listener(onSuccess = { _, _ ->
                findViewById<View>(R.id.fichaInicial).visibility = View.GONE
            })
        }
    }

    /** 142 minutos vira "2h 22min": é assim que se lê duração de filme. */
    private fun duracaoLegivel(minutos: Int): String =
        if (minutos < 60) getString(R.string.vod_ficha_minutos, minutos)
        else "${minutos / 60}h ${"%02d".format(minutos % 60)}min"

    /// As cores da classificação indicativa brasileira, as mesmas do celular.
    private fun corDaClassificacao(nota: String): Int = when (nota.uppercase()) {
        "L" -> 0xFF10B981.toInt()
        "10" -> 0xFF3B82F6.toInt()
        "12" -> 0xFFF59E0B.toInt()
        "14" -> 0xFFF97316.toInt()
        "16", "18" -> 0xFFEF4444.toInt()
        else -> 0xFFA8B4C0.toInt()
    }

    private fun selo(onde: LinearLayout, texto: String, cor: Int = 0xFFE6E6E6.toInt(), icone: Int = 0) {
        val dp = { v: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
                .toInt()
        }
        onde.addView(TextView(this).apply {
            text = texto
            setTextColor(cor)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setBackgroundResource(R.drawable.bg_selo)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            if (icone != 0) Icones.inicio(this, icone, 1.0f)
        })
    }

    private fun atualizarFavorito() {
        val marcado = VodFavoritos.contem(this, alvo.titulo, alvo.serie, alvo.ano)
        favoritar.text = getString(if (marcado) R.string.vod_ficha_favorito else R.string.vod_ficha_favoritar)
        Icones.inicio(favoritar, if (marcado) R.drawable.ic_star else R.drawable.ic_star_outline)
    }

    /**
     * Volta ao acervo levando o título: a tela de lá abre as fontes do filme
     * ou as temporadas da série, e as telas empilhadas por cima dela — esta
     * ficha e as de ator que vieram antes — fecham no caminho.
     */
    /** Toca direto: o filme de onde parou, a série no episódio em que está. */
    private fun assistir() {
        val r = resolvido
        val ep = r?.let { Titulos.ondeContinuar(this, it) }
        if (ep != null) PlayerActivity.abrir(this, alvo, ep.temporada, ep.numero)
        else PlayerActivity.abrir(this, alvo)
    }

    companion object {
        internal var arteSelecionada: Pair<Alvo, android.graphics.drawable.Drawable.ConstantState?>? = null
        private var previa: Pair<Alvo, android.graphics.drawable.Drawable.ConstantState?>? = null
        fun abrir(context: Context, alvo: Alvo) {
            val tela = context as? android.app.Activity
            previa = arteSelecionada?.takeIf { it.first == alvo } ?: if (tela?.currentFocus?.id == R.id.inicioInformacoes)
                alvo to tela.findViewById<ImageView>(R.id.inicioFundo)?.drawable?.constantState else null
            context.startActivity(alvo.em(Intent(context, FichaActivity::class.java)))
        }

        /// O crescimento no foco é a GPU, não uma nova medição de layout.
        internal val crescerNoFoco = View.OnFocusChangeListener { view, foco ->
            val escala = if (foco && (BuildConfig.TV_AO_VIVO || view !is TextView)) 1.06f else 1f
            view.animate().scaleX(escala).scaleY(escala).setDuration(120).start()
        }
    }
}

/** Episódios com miniaturas do TMDB e fallback visual para temporadas sem stills. */
@androidx.media3.common.util.UnstableApi
private class EpisodiosAdapter(
    private val escopo: CoroutineScope,
    private val aoEscolher: (Titulos.Ep) -> Unit,
) : RecyclerView.Adapter<EpisodiosAdapter.Holder>() {

    private var r: Titulos.Resolvido? = null
    var tmdbId: Int = 0
    var acima: Int = View.NO_ID
    private var itens: List<Titulos.Ep> = emptyList()
    private var info: Map<Int, Detalhes.EpisodioTmdb> = emptyMap()
    private var temporadaRespondida = false
    private var reserva: String? = null
    private val miniaturasExtras = mutableMapOf<Pair<Int, Int>, String>()
    private val consultasConcluidas = mutableSetOf<Pair<Int, Int>>()
    private val consultasIniciadas = mutableSetOf<Pair<Int, Int>>()

    fun atualizarReserva(url: String?) {
        if (url.isNullOrBlank() || url == reserva) return
        reserva = url
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun trocar(resolvido: Titulos.Resolvido, lista: List<Titulos.Ep>, dados: Map<Int, Detalhes.EpisodioTmdb>) {
        r = resolvido
        itens = lista
        info = dados
        temporadaRespondida = false
        notifyDataSetChanged()
    }

    fun completar(dados: Map<Int, Detalhes.EpisodioTmdb>) {
        info = dados
        temporadaRespondida = true
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val imagem: ImageView = view.findViewById(R.id.epImagem)
        val numero: TextView = view.findViewById(R.id.epNumeroGrande)
        val visto: TextView = view.findViewById(R.id.epVisto)
        val progresso: ProgressBar = view.findViewById(R.id.epProgresso)
        val titulo: TextView = view.findViewById(R.id.epTitulo)
        val detalhe: TextView = view.findViewById(R.id.epDetalhe)
        var chave: Pair<Int, Int>? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episodio, parent, false)
        val holder = Holder(view)
        view.setOnKeyListener { _, codigo, evento -> Cartoes.segurarNasPontas(holder, codigo, evento) }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ep = itens[position]
        val chave = ep.temporada to ep.numero
        holder.chave = chave
        val dados = info[ep.numero]
        val contexto = holder.itemView.context
        holder.numero.text = ep.numero.toString()
        holder.titulo.text = listOfNotNull("${ep.numero}.", dados?.nome?.takeIf { it.isNotBlank() }
            ?: contexto.getString(R.string.vod_episodio, ep.numero)).joinToString(" ")
        holder.detalhe.text = listOfNotNull(dados?.duracao?.let { "$it min" },
            ep.versoes.joinToString(" / ") { Titulos.rotulo(it) },
            dados?.sinopse?.takeIf { it.isNotBlank() }).joinToString(" · ")
        val fracao = r?.let { Progresso.fracao(contexto, it.chave(ep)) }
        holder.visto.visibility = if (fracao != null && fracao >= 1f) View.VISIBLE else View.GONE
        holder.progresso.visibility = if (fracao != null && fracao < 1f) View.VISIBLE else View.GONE
        fracao?.let { holder.progresso.progress = (it * 1000).toInt() }

        val imagem = dados?.imagem ?: miniaturasExtras[chave] ?: reserva
        exibirImagem(holder, chave, imagem, reserva.takeIf { it != imagem })

        // Só investiga /images para episódios VISÍVEIS e depois de receber
        // a temporada. Nunca baixa dezenas de imagens ao entrar na ficha.
        if (temporadaRespondida && dados?.imagem == null && tmdbId > 0 &&
            consultasIniciadas.add(chave)) {
            val idConsultado = tmdbId
            escopo.launch(semDerrubar) {
                val encontrada = Detalhes.imagemEpisodio(idConsultado, chave.first, dados?.numero ?: chave.second)
                if (tmdbId != idConsultado) return@launch
                consultasConcluidas.add(chave)
                if (encontrada != null) miniaturasExtras[chave] = encontrada
                val pos = itens.indexOfFirst { it.temporada == chave.first && it.numero == chave.second }
                if (pos >= 0) notifyItemChanged(pos)
            }
        }
        holder.itemView.setOnClickListener { aoEscolher(ep) }
        holder.itemView.nextFocusUpId = acima
    }

    private fun exibirImagem(holder: Holder, chave: Pair<Int, Int>, url: String?, alternativa: String?) {
        holder.imagem.dispose()
        holder.imagem.setImageDrawable(null)
        if (url.isNullOrBlank()) {
            holder.imagem.visibility = View.GONE
            holder.numero.visibility = if (temporadaRespondida && (tmdbId <= 0 || chave in consultasConcluidas)) View.VISIBLE else View.INVISIBLE
            return
        }
        holder.imagem.visibility = View.VISIBLE
        holder.numero.visibility = View.INVISIBLE
        fun carregar(endereco: String, permitirReserva: Boolean) {
            holder.imagem.load(endereco) {
                crossfade(150)
                listener(
                    onError = { _, _ ->
                        if (holder.chave != chave) return@listener
                        if (permitirReserva && !alternativa.isNullOrBlank()) {
                            carregar(alternativa, false)
                        } else {
                            holder.imagem.visibility = View.GONE
                            holder.numero.visibility = View.VISIBLE
                        }
                    }
                )
            }
        }
        carregar(url, true)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.imagem.dispose()
        holder.imagem.setImageDrawable(null)
        holder.chave = null
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = itens.size
}
