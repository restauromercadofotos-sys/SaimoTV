package br.com.lumeflix.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A aba do menu do topo que cada tela representa. */
enum class Aba { INICIO, AO_VIVO, EVENTOS, ON_DEMAND, SERIES, FAVORITOS, RADIOS, EXTRAS, BUSCAR, APOIE, AJUSTES, NENHUMA }

/**
 * O menu do topo, o mesmo em todas as telas.
 *
 * Antes ele só existia na tela inicial: de dentro de uma ficha, para ir às
 * séries era VOLTAR, VOLTAR, VOLTAR. Agora a seta para cima, de qualquer
 * tela, chega nele — e de lá tudo está a um OK.
 *
 * Navegar pelo menu não empilha telas: volta à inicial e abre o destino por
 * cima dela, então VOLTAR sempre leva à tela inicial, nunca a um labirinto.
 */
@UnstableApi
object MenuGlobal {

    fun criar(tela: Activity, atual: Aba, aoFocar: (() -> Unit)? = null, aoInicio: (() -> Unit)? = null): View {
        val d = tela.resources.displayMetrics.density
        val barra = LinearLayout(tela).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((40 * d).toInt(), (18 * d).toInt(), (40 * d).toInt(), (4 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        barra.addView(if (BuildConfig.TV_AO_VIVO) ImageView(tela).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()).apply { marginEnd = (8 * d).toInt() }
        } else ImageView(tela).apply {
            // Logo vetorial por extenso: não precisa carregar imagem da rede,
            // decodificar bitmap grande ou carregar uma fonte adicional.
            setImageResource(R.drawable.lumeflix_wordmark)
            contentDescription = tela.getString(R.string.app_name)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams((156 * d).toInt(),
                (30 * d).toInt()).apply { marginEnd = (22 * d).toInt() }
        })
        val rolagem = HorizontalScrollView(tela).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            // A rolagem em si não recebe foco — só as abas.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val abas = LinearLayout(tela).apply { orientation = LinearLayout.HORIZONTAL; id = R.id.menuGlobalAbas }
        rolagem.addView(abas)
        barra.addView(rolagem, 0)

        val itens = if (BuildConfig.TV_AO_VIVO) listOf(
            Triple(Aba.INICIO, R.string.menu_inicio, R.drawable.ic_home),
            Triple(Aba.AO_VIVO, R.string.menu_ao_vivo, R.drawable.ic_live_tv),
            Triple(Aba.EVENTOS, R.string.menu_eventos, R.drawable.ic_football),
            Triple(Aba.AJUSTES, R.string.menu_ajustes, R.drawable.ic_settings),
        ) else listOf(
            Triple(Aba.BUSCAR, R.string.menu_buscar, R.drawable.ic_search),
            Triple(Aba.INICIO, R.string.menu_inicio, 0),
            Triple(Aba.ON_DEMAND, R.string.vod_filmes, 0),
            Triple(Aba.SERIES, R.string.vod_series, 0),
            Triple(Aba.FAVORITOS, R.string.vod_favoritos, 0),
            Triple(Aba.AJUSTES, R.string.menu_ajustes, R.drawable.ic_settings),
        )

        var navegacaoPendente: Runnable? = null
        for ((aba, texto, icone) in itens.filter { Conteudo.permiteAba(it.first) }) {
            abas.addView(TextView(tela).apply {
                text = tela.getString(texto)
                contentDescription = text
                tag = aba
                textSize = if (BuildConfig.TV_AO_VIVO) 15f else 14f
                maxLines = 1
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(ContextCompat.getColorStateList(tela, R.color.texto_botao_ficha))
                setBackgroundResource(R.drawable.aba_menu)
                isFocusable = true
                isSelected = aba == atual
                // Extras só existe depois do código, e some de novo sem deixar vão.
                if (aba == Aba.EXTRAS) visibility = if (Unlock.unlocked) View.VISIBLE else View.GONE
                setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (1 * d).toInt() }
                when {
                    aba == Aba.AJUSTES || aba == Aba.BUSCAR -> Icones.so(this, icone)
                    icone != 0 -> Icones.inicio(this, icone)
                }
                // Foco sozinho não navega: o Android pode passar por esta aba
                // ao mover o foco entre a barra e o conteúdo. Esquerda/direita
                // abaixo abrem explicitamente o destino, sem trocar de tela ao
                // entrar na barra vindo das categorias.
                setOnKeyListener { v, codigo, evento ->
                    if (evento.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    if (abas.orientation == LinearLayout.VERTICAL) return@setOnKeyListener false
                    val passo = when (codigo) {
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        else -> return@setOnKeyListener false
                    }
                    var i = abas.indexOfChild(v) + passo
                    while (i in 0 until abas.childCount && abas.getChildAt(i).visibility != View.VISIBLE) i += passo
                    val destino = abas.getChildAt(i)
                    if (destino != null && destino.requestFocus()) {
                        navegacaoPendente?.let(abas::removeCallbacks)
                        navegacaoPendente = Runnable {
                            if (destino.isFocused && destino.isAttachedToWindow && !tela.isFinishing)
                                ir(tela, destino.tag as Aba, atual, peloDirecional = true)
                        }.also { abas.postDelayed(it, 140) }
                    }
                    true
                }
                setOnClickListener {
                    navegacaoPendente?.let(abas::removeCallbacks)
                    if (aba == Aba.INICIO && aoInicio != null) aoInicio() else ir(tela, aba, atual)
                }
            })
        }

        val relogio = TextView(tela).apply {
            textSize = 18f
            setTextColor(ContextCompat.getColor(tela, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (12 * d).toInt() }
        }
        if (BuildConfig.TV_AO_VIVO) barra.addView(relogio)
        val hora = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        val handler = Handler(Looper.getMainLooper())
        val tique = object : Runnable {
            override fun run() {
                relogio.text = hora.format(Date())
                if (relogio.isAttachedToWindow || !tela.isFinishing) handler.postDelayed(this, 20_000)
            }
        }
        barra.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { handler.post(tique) }
            override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(tique) }
        })
        return barra
    }

    /** Foca a aba da tela atual (ou a primeira): é para onde VOLTAR leva na inicial. */
    fun focar(barra: View?, aba: Aba) {
        val abas = barra?.findViewById<LinearLayout>(R.id.menuGlobalAbas) ?: return
        (abas.findViewWithTag<View>(aba) ?: abas.getChildAt(0))?.requestFocus()
    }

    fun temFoco(barra: View?) = barra?.hasFocus() == true

    /** Mostra ou esconde o Extras conforme o código: o menu já montado não se refaz sozinho. */
    fun atualizar(barra: View?) {
        val abas = barra?.findViewById<LinearLayout>(R.id.menuGlobalAbas) ?: return
        abas.findViewWithTag<View>(Aba.EXTRAS)?.visibility = if (Unlock.unlocked) View.VISIBLE else View.GONE
    }

    // As abas normalmente já têm o Início abaixo delas na pilha da tarefa.
    internal fun podeNavegarDireto(estaNoInicio: Boolean, ehRaiz: Boolean) =
        estaNoInicio || !ehRaiz

    private fun ir(tela: Activity, aba: Aba, atual: Aba, peloDirecional: Boolean = false) {
        if (aba == atual || !Conteudo.permiteAba(aba)) return
        val inicio = Intent(tela, EscolhaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val destino: Intent? = when (aba) {
            Aba.INICIO, Aba.NENHUMA -> null
            Aba.AO_VIVO -> Intent(tela, MainActivity::class.java)
            Aba.EVENTOS -> Intent(tela, EventosActivity::class.java)
            Aba.ON_DEMAND -> PaginaActivity.intent(tela, GradeActivity.FILMES)
            Aba.SERIES -> PaginaActivity.intent(tela, GradeActivity.SERIES)
            Aba.FAVORITOS -> GradeActivity.intent(tela, GradeActivity.FAVORITOS)
            Aba.RADIOS -> Intent(tela, RadiosActivity::class.java)
            Aba.EXTRAS -> GradeActivity.intent(tela, GradeActivity.EXTRAS)
            Aba.BUSCAR -> Intent(tela, BuscaActivity::class.java)
            Aba.AJUSTES -> Intent(tela, AjustesActivity::class.java)
            Aba.APOIE -> Intent(tela, DoacaoActivity::class.java)
        }
        (destino ?: inicio).putExtra("lumeflix.focoMenu", peloDirecional)
        if (destino == null) {
            tela.startActivity(inicio)
        } else if (podeNavegarDireto(tela is EscolhaActivity, tela.isTaskRoot)) {
            // Não reabre o Início apenas para chegar a outra aba.
            tela.startActivity(destino)
            if (tela !is EscolhaActivity) tela.finish()
        } else {
            // Uma Activity aberta por link pode não ter o Início abaixo dela.
            tela.startActivities(arrayOf(inicio, destino))
        }
        tela.overridePendingTransition(0, 0)
    }
}

/**
 * Tela com o menu do topo e a imagem de fundo do app.
 *
 * Quem herda daqui chama `setContentView(layout)` como sempre: o menu entra
 * em cima do layout, sem nenhum XML repetido.
 */
@UnstableApi
abstract class TelaComMenu : AppCompatActivity() {

    protected open val aba: Aba = Aba.NENHUMA
    protected open fun aoFocarMenu() {}
    protected open val aoMenuInicio: (() -> Unit)? = null
    protected var barraMenu: View? = null
        private set
    private var vazioView: View? = null
    private var conteudoView: View? = null

    /**
     * Estado vazio da tela: [mensagem] em cima do conteúdo e um botão que abre
     * o diálogo de endereços. Sem dado nenhum, a tela continua navegável (o
     * menu do topo segue alcançável com a seta para cima).
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

    protected fun vazioVisivel(): Boolean = vazioView?.visibility == View.VISIBLE

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
        AddonMenu.abrir(this)
    }

    /** Os endereços mudaram e os dados já foram pedidos de novo. */
    protected open fun aoMudarListas() { if (!isFinishing && !isDestroyed) recreate() }

    /**
     * Foca o que `achar` devolver assim que existir — listas desenham um quadro
     * depois de receberem os dados, e um único `post` às vezes chega cedo.
     */
    protected fun focarQuandoPronto(tentativas: Int = 20, achar: () -> View?) {
        if (barraMenu?.hasFocus() == true) return
        val alvo = achar()
        if (alvo?.requestFocus() == true) return
        if (tentativas > 0) window.decorView.postDelayed({ focarQuandoPronto(tentativas - 1, achar) }, 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(if (BuildConfig.TV_AO_VIVO) R.drawable.fundo_app else R.drawable.fundo_lumeflix)
    }

    override fun onResume() {
        super.onResume()
        MenuGlobal.atualizar(barraMenu)
    }

    private var ultimoCartaoLateral: View? = null
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val abas = barraMenu?.findViewById<LinearLayout>(R.id.menuGlobalAbas)
        if (abas?.orientation == LinearLayout.VERTICAL && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val foco = currentFocus
            if (abas.hasFocus() && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                ultimoCartaoLateral?.takeIf { it.isAttachedToWindow }?.requestFocus() == true) return true
            val fila = foco?.parent as? androidx.recyclerview.widget.RecyclerView
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && fila?.id == R.id.filaCapas &&
                fila.getChildAdapterPosition(foco) == 0) {
                ultimoCartaoLateral = foco
                MenuGlobal.focar(barraMenu, aba)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun setContentView(layoutResID: Int) {
        // Recorta cada parte no próprio espaço: a imagem larga da ficha, sem
        // isso, desenhava por cima do menu.
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val barra = if (BuildConfig.TV_AO_VIVO) null
                    else MenuGlobal.criar(this, aba, { aoFocarMenu() }, aoMenuInicio)
        barraMenu = barra
        val sobreCapa = this is EscolhaActivity || this is PaginaActivity
        if (!sobreCapa) barra?.let(raiz::addView)
        // O conteúdo e, por cima, o estado vazio (escondido até alguém pedir).
        val moldura = android.widget.FrameLayout(this)
        val conteudo = layoutInflater.inflate(layoutResID, moldura, false)
        moldura.addView(conteudo)
        conteudoView = conteudo
        val vazio = layoutInflater.inflate(R.layout.estado_vazio, moldura, false)
        moldura.addView(vazio)
        if (sobreCapa && barra != null) {
            moldura.addView(barra, android.widget.FrameLayout.LayoutParams(-1, -2))
            barra.findViewById<View>(R.id.menuGlobalAbas).background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xDC101010.toInt())
                cornerRadius = resources.displayMetrics.density * 24
            }
            val banner = conteudo.findViewById<View>(R.id.inicioBanner)
            val lista = conteudo.findViewById<View>(R.id.inicioFilas)
            val abas = barra.findViewById<LinearLayout>(R.id.menuGlobalAbas)
            val grupo = barra as LinearLayout
            val marca = grupo.getChildAt(1)
            val inicio = lista.paddingLeft
            val fundoTopo = abas.background
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d + 0.5f).toInt()
            var estado: Pair<Boolean, Boolean>? = null
            var focoAnterior: View? = null
            fun atualizarMenu() {
                val lateral = banner.visibility == View.GONE
                val foco = if (lateral) abas.findFocus() else null
                val novo = lateral to (foco != null)
                if (estado == novo) {
                    if (lateral && focoAnterior !== foco) {
                        (focoAnterior as? TextView)?.apply {
                            setTextColor(0xFFAAAAAA.toInt())
                            typeface = android.graphics.Typeface.create("sans-serif", 0)
                        }
                        (foco as? TextView)?.apply {
                            setTextColor(-1)
                            typeface = android.graphics.Typeface.create("sans-serif", 1)
                        }
                        focoAnterior = foco
                    }
                    return
                }
                estado = novo
                focoAnterior = foco
                val expandido = foco != null
                abas.orientation = if (lateral) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                abas.background = if (lateral) null else fundoTopo
                marca.visibility = if (lateral) View.GONE else View.VISIBLE
                grupo.background = if (expandido) android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF090909.toInt(), 0xF3090909.toInt(), 0x00090909)) else null
                grupo.setPadding(dp(if (lateral) 8 else 40), dp(if (lateral) 0 else 18),
                    dp(if (lateral) 0 else 40), dp(if (lateral) 0 else 4))
                grupo.layoutParams = android.widget.FrameLayout.LayoutParams(
                    if (expandido) dp(216) else if (lateral) dp(48) else -1,
                    if (lateral) -1 else -2, if (lateral) Gravity.START else Gravity.TOP)
                for (i in 0 until abas.childCount) {
                    val item = abas.getChildAt(i) as TextView
                    val destino = item.tag as Aba
                    val icone = when (destino) {
                        Aba.BUSCAR -> R.drawable.ic_search
                        Aba.INICIO -> R.drawable.ic_home
                        Aba.ON_DEMAND -> R.drawable.ic_movie
                        Aba.SERIES -> R.drawable.ic_episodes
                        Aba.FAVORITOS -> R.drawable.ic_favorite
                        else -> R.drawable.ic_settings
                    }
                    item.text = item.contentDescription
                    item.minWidth = 0
                    item.minHeight = dp(if (lateral) 40 else 0)
                    if (lateral) {
                        item.background = null
                        if (android.os.Build.VERSION.SDK_INT >= 26) item.setDefaultFocusHighlightEnabled(false)
                        item.setTextColor(if (item.isFocused || (!expandido && item.isSelected)) -1 else 0xFFAAAAAA.toInt())
                        item.typeface = android.graphics.Typeface.create("sans-serif", if (item.isFocused) 1 else 0)
                        if (expandido) Icones.inicio(item, icone, 1.4f) else Icones.so(item, icone)
                        item.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    } else {
                        item.typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                        item.setTextColor(ContextCompat.getColorStateList(this, R.color.texto_botao_ficha))
                        item.setBackgroundResource(R.drawable.aba_menu)
                        if (destino == Aba.BUSCAR || destino == Aba.AJUSTES) Icones.so(item, icone)
                        else Icones.inicio(item, 0)
                    }
                }
                lista.setPadding(inicio + if (lateral) dp(48) else 0, lista.paddingTop, lista.paddingRight, lista.paddingBottom)
            }
            // Mudar orientação/padding durante onLayout provoca passagens extras e perda de quadros.
            var atualizacaoPendente = false
            fun agendarAtualizacao() {
                if (atualizacaoPendente) return
                atualizacaoPendente = true
                lista.post {
                    atualizacaoPendente = false
                    if (lista.isAttachedToWindow) atualizarMenu()
                }
            }
            lista.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> agendarAtualizacao() }
            grupo.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> agendarAtualizacao() }
        }
        vazioView = vazio
        vazio.findViewById<TextView>(R.id.vazioBotao).apply {
            Icones.inicio(this, R.drawable.ic_link)
            text = "Addons do Stremio"
            setOnClickListener { pedirLista() }
        }
        vazio.findViewById<TextView>(R.id.vazioBotaoSaimo).apply {
            Icones.inicio(this, R.drawable.ic_settings)
            setOnClickListener { pedirEnderecos() }
        }
        raiz.addView(moldura, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        super.setContentView(raiz)
        if (barra == null) return
        // O sistema dá o primeiro foco ao primeiro focável da tela — que seria
        // a aba "Início". Enquanto o conteúdo não pega o foco, o menu fica de
        // fora; depois (ou em dois segundos, se o conteúdo não tiver nada), a
        // seta para cima chega nele normalmente.
        val abas = barra.findViewById<ViewGroup>(R.id.menuGlobalAbas)
        if (intent.getBooleanExtra("lumeflix.focoMenu", false)) {
            MenuGlobal.focar(barra, aba)
        } else abas.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val liberar = Runnable { abas.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }

        fun dentroDoMenu(view: View?): Boolean {
            var atual = view
            while (atual != null) {
                if (atual === barra) return true
                atual = atual.parent as? View
            }
            return false
        }
        raiz.viewTreeObserver.addOnGlobalFocusChangeListener { anterior, novo ->
            if (novo != null && abas.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) liberar.run()
            // Ao subir do catálogo para a barra, o Android escolhia a aba mais
            // à esquerda ("Início") pela geometria, mesmo estando em Filmes
            // ou Séries. Redireciona a PRIMEIRA entrada para a aba atual.
            // Navegação entre abas com esquerda/direita continua livre.
            if (novo != null && dentroDoMenu(novo) && !dentroDoMenu(anterior)) {
                val atual = abas.findViewWithTag<View>(aba)
                if (atual != null && atual !== novo && atual.visibility == View.VISIBLE) {
                    atual.requestFocus()
                }
            }
        }
        raiz.postDelayed(liberar, 2_000)
    }
}
