package br.com.lumeflix.app

import android.app.Activity
import android.widget.ImageView
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.load
import coil3.dispose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * O destaque do topo: o título focado, com a imagem larga dele ao fundo.
 *
 * Espera o foco descansar um pouco antes de pedir a ficha: quem corre a
 * fileira com a seta não precisa de um pedido ao TMDB por capa que passa.
 *
 * Quando o catálogo publica um trailer direto (MP4 ou HLS, sexto campo de
 * destaques.txt), ele começa sem som depois de 5 s de foco parado, no lugar
 * da imagem. A ficha só abre quando o usuário seleciona o cartão.
 */
@UnstableApi
class Destaque(
    private val tela: Activity,
    private val escopo: CoroutineScope,
    private val fundo: ImageView,
    private val titulo: TextView,
    private val meta: TextView,
    private val sinopse: TextView,
    private val aoAbrir: (Inicio.Cartao) -> Unit = {},
) {
    private val logoTitulo = ImageView(tela).apply {
        visibility = View.GONE
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        val d = tela.resources.displayMetrics.density
        layoutParams = android.widget.LinearLayout.LayoutParams((260*d).toInt(), (72*d).toInt())
    }.also { imagem ->
        (titulo.parent as? android.widget.LinearLayout)?.let { it.addView(imagem, it.indexOfChild(titulo)) }
    }
    private var trabalho: Job? = null
    private var trailerJob: Job? = null
    private var trailerPlayer: ExoPlayer? = null
    /// Logo acima da imagem e abaixo dos degradês do grupo, que continuam
    /// deixando o texto legível por cima do vídeo.
    private val trailerView: PlayerView? = (fundo.parent as? ViewGroup)?.let { grupo ->
        (tela.layoutInflater.inflate(R.layout.trailer_destaque, grupo, false) as PlayerView).also {
            grupo.addView(it, grupo.indexOfChild(fundo) + 1)
        }
    }
    /// O trailer só conta como "tocou" depois do primeiro quadro na tela.
    private var trailerMostrou = false

    init {
        // Tela que sai da frente (abriu a ficha, o player, foi para o
        // sistema) não deixa vídeo decodificando nem contagem correndo.
        (tela as? LifecycleOwner)?.lifecycle?.addObserver(LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_PAUSE || evento == Lifecycle.Event.ON_DESTROY) {
                trailerJob?.cancel()
                pararTrailer()
            }
        })
    }
    private var fundoAtual: String? = null

    fun padrao(tituloPadrao: String, metaPadrao: String) {
        cartaoAtual = null
        trabalho?.cancel()
        trailerJob?.cancel()
        pararTrailer()
        logoTitulo.dispose()
        logoTitulo.visibility = View.GONE
        titulo.visibility = View.VISIBLE
        titulo.text = tituloPadrao
        meta.text = metaPadrao
        sinopse.text = ""
        trocarFundo(null)
    }

    fun mostrar(cartao: Inicio.Cartao) {
        trabalho?.cancel()
        trailerJob?.cancel()
        pararTrailer()
        cartaoAtual = cartao
        aguardarPrevia()
        logoTitulo.dispose()
        logoTitulo.visibility = View.GONE
        val alvo = cartao.alvo
        titulo.visibility = View.VISIBLE
        trocarFundo(cartao.capaLarga ?: cartao.capa.takeIf { it.isNotBlank() })
        titulo.text = if (alvo != null) Generos.semAno(alvo.titulo) else cartao.titulo
        meta.text = cartao.meta
            ?: cartao.subtitulo?.let { tela.getString(R.string.inicio_canal_agora, it) }
            ?: alvo?.let { tela.getString(if (it.serie) R.string.vod_ficha_tipo_serie else R.string.vod_ficha_tipo_filme) }
                .orEmpty()
        sinopse.text = cartao.sinopse.orEmpty()
        if (alvo == null) {
            // Cartão que não é título (categoria): o fundo é o de um título dela.
            val de = cartao.fundoDe ?: run { trocarFundo(null); return }
            trabalho = escopo.launch(semDerrubar) {
                delay(250)
                trocarFundo(Detalhes.de(de.titulo, de.serie, de.tmdbId.takeIf { it > 0 })?.fundo)
            }
            return
        }
        trabalho = escopo.launch(semDerrubar) {
            delay(120)
            val ficha = Detalhes.de(alvo.titulo, alvo.serie, alvo.tmdbId.takeIf { it > 0 })
            if (ficha == null) {
                titulo.visibility = View.VISIBLE
                return@launch
            }
            titulo.text = ficha.titulo.ifBlank { Generos.semAno(alvo.titulo) }
            val urlLogo = ficha.logo
            if (urlLogo.isNullOrBlank()) {
                titulo.visibility = View.VISIBLE
            } else {
                logoTitulo.contentDescription = titulo.text
                logoTitulo.visibility = View.GONE
                logoTitulo.load(urlLogo) {
                    listener(
                        onSuccess = { _, _ ->
                            logoTitulo.visibility = View.VISIBLE
                            titulo.visibility = View.GONE
                        },
                        onError = { _, _ ->
                            logoTitulo.visibility = View.GONE
                            titulo.visibility = View.VISIBLE
                        },
                    )
                }
            }
            val linha = listOfNotNull(
                ficha.nota.takeIf { it > 0 }?.let { "%.1f".format(it) },
                ficha.ano.takeIf { it.isNotBlank() },
                ficha.temporadas?.let { tela.resources.getQuantityString(R.plurals.vod_ficha_temporadas_n, it, it) },
                ficha.generos.take(3).joinToString(", ").takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            meta.text = if (ficha.nota > 0) Icones.emLinha(tela, R.drawable.ic_star, linha,
                meta.textSize.toInt(), meta.currentTextColor) else linha
            sinopse.text = ficha.sinopse
            ficha.fundo?.let { trocarFundo(it) }
            ficha.fundo?.let { imagem ->
                cartao.capaLarga = imagem
                cartao.atualizarCapaLarga?.invoke(imagem)
            }
        }
    }

    private var cartaoAtual: Inicio.Cartao? = null

    fun aguardarPrevia() {
        trailerJob?.cancel()
        pararTrailer()
        val cartao = cartaoAtual ?: return
        val trailer = cartao.trailer?.takeIf { TRAILER_DIRETO.matches(it) }
        if (cartao.alvo != null) {
            trailerJob = escopo.launch(semDerrubar) {
                delay(5_000)
                val vida = (tela as LifecycleOwner).lifecycle
                if (!vida.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                val url = trailer ?: trechoDoCatalogo(cartao.alvo) ?: return@launch
                if (vida.currentState.isAtLeast(Lifecycle.State.RESUMED)) iniciarTrailer(url)
            }
        }
    }

    private suspend fun trechoDoCatalogo(alvo: Alvo): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val item = Vod.Achado(alvo.titulo, alvo.serie, alvo.letra, alvo.ano)
        if (!alvo.serie) {
            val filme = Vod.filmes(tela, alvo.letra).firstOrNull { it.titulo == alvo.titulo }
            return@withContext filme?.let { Titulos.ordem(it.fontes, Preferencias.versao).firstOrNull()?.url }
        }
        val episodios = if (alvo.colecao.isNotEmpty()) {
            Vod.colecao(tela, alvo.colecao).firstOrNull { it.titulo == alvo.titulo }?.episodios
        } else {
            Vod.series(tela, alvo.letra).firstOrNull { it.titulo == item.titulo &&
                (item.ano.isBlank() || it.ano == item.ano) }?.let { Vod.episodios(tela, alvo.letra, it) }
        }
        episodios?.firstOrNull { it.temporada == 1 && it.numero == 1 }?.urls?.firstOrNull()
    }

    fun suspender() {
        cartaoAtual = null
        trabalho?.cancel()
        trailerJob?.cancel()
        pararTrailer()
    }

    private fun iniciarTrailer(url: String) {
        val view = trailerView ?: return
        val carga = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 3_000, 600, 1_000).setTargetBufferBytes(4 * 1024 * 1024).build()
        val p = ExoPlayer.Builder(tela).setLoadControl(carga).build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720)
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build()
        trailerPlayer = p
        trailerMostrou = false
        view.alpha = 0f
        view.player = p
        view.visibility = View.VISIBLE
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                trailerMostrou = true
                view.animate().alpha(1f).setDuration(400).start()
                // Título sem imagem larga deixa o grupo apagado; com trailer ele aparece.
                (fundo.parent as? View)?.takeIf { it.id == R.id.inicioFundoGrupo }
                    ?.animate()?.alpha(1f)?.setDuration(300)?.start()
            }
            // Endereço fora do ar ou recusado: volta a imagem, e nada abre sozinho.
            override fun onPlayerError(error: PlaybackException) { view.post { if (trailerPlayer === p) pararTrailer() } }
        })
        p.setMediaSource(Playback.mediaSource(tela, Source(url)))
        p.repeatMode = ExoPlayer.REPEAT_MODE_ONE
        p.volume = 0f
        p.prepare()
        p.play()
        trailerJob = escopo.launch { delay(11_000); if (trailerPlayer === p) pararTrailer() }
    }
    private fun pararTrailer() {
        trailerMostrou = false
        if (trailerPlayer == null) return
        trailerView?.player = null
        trailerView?.animate()?.cancel()
        trailerView?.alpha = 0f
        trailerView?.visibility = View.GONE
        trailerPlayer?.release()
        trailerPlayer = null
    }

    private fun trocarFundo(endereco: String?) {
        if (endereco == fundoAtual) return
        fundoAtual = endereco
        // Anima o grupo (imagem + degradês) quando existe: sem título, só o
        // fundo do app fica à vista.
        val alvo = (fundo.parent as? android.view.View)?.takeIf { it.id == R.id.inicioFundoGrupo } ?: fundo
        if (endereco == null) {
            alvo.animate().alpha(0f).setDuration(200).withLayer().start()
            return
        }
        fundo.load(endereco) {
            listener(onSuccess = { _, _ -> alvo.animate().alpha(1f).setDuration(300).withLayer().start() })
        }
    }

    private companion object {
        /// Só vídeo direto: página do YouTube e afins não tocam no player.
        val TRAILER_DIRETO = Regex("^https?://.*\\.(mp4|m3u8)(\\?.*)?$", RegexOption.IGNORE_CASE)
    }
}
