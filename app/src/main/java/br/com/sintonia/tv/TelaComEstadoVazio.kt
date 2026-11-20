package br.com.sintonia.tv

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi

@UnstableApi
abstract class TelaComEstadoVazio : AppCompatActivity() {

    private var vazioView: View? = null
    private var conteudoView: View? = null

    /**
     * Estado vazio da tela: [mensagem] em cima do conteúdo e um botão que abre
     * diálogo de endereços.
     */
    protected fun mostrarVazio(mensagem: String) {
        val v = vazioView ?: return
        v.findViewById<TextView>(R.id.vazioTexto).text = mensagem
        if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
        // O conteúdo (listas vazias, mas focáveis) por baixo não toma mais o
        // foco: antes a seta para baixo parava nele e o controle não chegava
        // aos botões de adicionar lista.
        (conteudoView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // O foco vai para o primeiro botão (a lista de baixo, vazia, o toma
        // quando a tela abre): sem isto o controle remoto não alcança nada.
        focarBotaoDoVazio()
        v.postDelayed({ focarBotaoDoVazio() }, 300)
    }

    /** Leva o foco ao primeiro botão do estado vazio, se ele está na tela. */
    protected fun focarBotaoDoVazio() {
        val v = vazioView ?: return
        if (v.visibility == View.VISIBLE && !v.hasFocus()) v.findViewById<View>(R.id.vazioBotao).requestFocus()
    }

    protected fun esconderVazio() {
        val v = vazioView ?: return
        if (v.visibility == View.VISIBLE) {
            v.visibility = View.GONE
        }
        (conteudoView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    /** Abre "Endereço das listas"; depois de salvar, a tela se refaz. */
    protected fun pedirEnderecos() {
        ListasDialogo.mostrar(this) { aoMudarListas() }
    }

    /** Tela vazia: adicionar uma lista M3U ou com usuário e senha. */
    protected fun pedirLista() {
        MinhasListasTela.adicionarPorLink(this) { aoMudarListas() }
    }

    /** Os endereços mudaram e os dados já foram pedidos de novo. */
    protected open fun aoMudarListas() { if (!isFinishing && !isDestroyed) recreate() }

    /**
     * Foca o que `achar` devolver assim que existir — listas desenham um quadro
     * depois de receberem os dados, e um único `post` às vezes chega cedo.
     */
    protected fun focarQuandoPronto(tentativas: Int = 20, achar: () -> View?) {
        val alvo = achar()
        if (alvo?.requestFocus() == true) return
        if (tentativas > 0) window.decorView.postDelayed({ focarQuandoPronto(tentativas - 1, achar) }, 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.drawable.fundo_app)
    }

    override fun setContentView(layoutResID: Int) {
        val moldura = FrameLayout(this)
        val conteudo = layoutInflater.inflate(layoutResID, moldura, false)
        moldura.addView(conteudo)
        conteudoView = conteudo
        val vazio = layoutInflater.inflate(R.layout.estado_vazio, moldura, false)
        moldura.addView(vazio)
        vazioView = vazio
        vazio.findViewById<TextView>(R.id.vazioBotao).apply {
            Icones.inicio(this, R.drawable.ic_link)
            setOnClickListener { pedirLista() }
        }
        vazio.findViewById<TextView>(R.id.vazioBotaoCatalogo).apply {
            Icones.inicio(this, R.drawable.ic_settings)
            setOnClickListener { pedirEnderecos() }
        }
        super.setContentView(moldura)
    }
}
