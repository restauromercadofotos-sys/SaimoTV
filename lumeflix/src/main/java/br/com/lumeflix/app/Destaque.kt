package br.com.lumeflix.app

import android.app.Activity
import android.widget.ImageView
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.util.UnstableApi
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
    private var fundoAtual: String? = null

    fun padrao(tituloPadrao: String, metaPadrao: String) {
        trabalho?.cancel()
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

    fun suspender() { trabalho?.cancel() }

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

}
