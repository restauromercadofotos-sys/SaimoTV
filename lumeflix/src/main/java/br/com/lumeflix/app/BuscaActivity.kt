package br.com.lumeflix.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A busca, uma só para tudo: canais, filmes, séries, animes, doramas e atores.
 *
 * Antes: digitar tudo, apertar "Buscar", esperar uma lista de nomes — e anime
 * e dorama nem entravam. Agora os resultados mudam a cada letra, em fileiras de
 * capa, com o microfone para quem tem e o teclado físico para quem tem.
 */
@UnstableApi
class BuscaActivity : TelaComMenu(), Connect.Listener {

    override val aba = Aba.BUSCAR


    companion object {
        private const val TERMO = "busca.termo"
        fun abrir(context: Context, termo: String? = null) =
            context.startActivity(Intent(context, BuscaActivity::class.java).putExtra(TERMO, termo))
    }

    private lateinit var campo: TextView
    private lateinit var estado: TextView
    private lateinit var resultados: RecyclerView
    private val digitado = StringBuilder()
    private var trabalho: Job? = null
    private val adaptador by lazy { Inicio.Adapter(escopo = lifecycleScope, capaDe = { t, s -> Generos.capa(t, s) }) }

    private var ultimoFocoTeclado: View? = null

    private val voz = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val dito = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@registerForActivityResult
        digitado.clear(); digitado.append(dito)
        atualizar(imediato = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_busca)
        campo = findViewById(R.id.buscaTermo)
        estado = findViewById(R.id.buscaEstado)
        resultados = findViewById(R.id.buscaResultados)
        resultados.layoutManager = Inicio.Filas(this)
        resultados.adapter = adaptador
        montarTeclado()
        intent.getStringExtra(TERMO)?.let { digitado.append(it) }

        // Prepara o índice enquanto a pessoa ainda procura a primeira letra.
        lifecycleScope.launch(semDerrubar) { Generos.carregar(this@BuscaActivity); Vod.entradas(this@BuscaActivity) }
        atualizar(imediato = true)
    }

    override fun onResume() {
        super.onResume()
        Connect.listener = this
        Connect.notificarMudancaDeEstado()
    }

    override fun onPause() {
        super.onPause()
        if (Connect.listener === this) Connect.listener = null
        Connect.notificarMudancaDeEstado()
    }

    override fun obterEstado(): org.json.JSONObject = org.json.JSONObject().apply {
        put("t", "state")
        put("mode", "idle")
        put("screen", "search")
        put("channel", "")
        put("playing", false)
        put("multi", false)
        put("active", 0)
        put("screens", org.json.JSONArray())
        put("searchText", digitado.toString())
    }

    override fun preencherTexto(texto: String) {
        digitado.clear()
        digitado.append(texto)
        atualizar(imediato = true)
        Connect.notificarMudancaDeEstado()
    }

    override fun sintonizarCanal(nome: String, metade: Int?) {
        if (!BuildConfig.TV_AO_VIVO) return
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, nome))
    }

    override fun simularTecla(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            apagar()
            Connect.notificarMudancaDeEstado()
            return true
        }
        val now = android.os.SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0)
        val consumed = dispatchKeyEvent(down)
        dispatchKeyEvent(up)
        if (!consumed) {
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> 0
            }
            if (direction != 0) {
                val current = currentFocus ?: window.decorView.findFocus()
                val next = current?.focusSearch(direction) ?: (window.decorView as? android.view.ViewGroup)?.let {
                    android.view.FocusFinder.getInstance().findNextFocus(it, current, direction)
                }
                next?.requestFocus(direction)
            }
        }
        return true
    }

    private fun montarTeclado() {
        val grade = findViewById<GridLayout>(R.id.buscaTeclas)
        grade.addView(tecla(getString(R.string.busca_espaco), 3) { digitar(" ") })
        grade.addView(tecla(getString(R.string.busca_apagar), 3) { apagar() })
        val teclas = ('a'..'z') + ('1'..'9') + '0'
        for (t in teclas) grade.addView(tecla(t.toString(), 1) { digitar(t.toString()) })
        grade.addView(tecla(getString(R.string.busca_limpar), 3) { digitado.clear(); atualizar(true) })
        val temVoz = packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
        if (temVoz) grade.addView(tecla(getString(R.string.busca_voz), 3) { ouvir() }.also { Icones.so(it as TextView, R.drawable.ic_mic, 1.2f) })
        focarQuandoPronto { grade.getChildAt(2) }
    }

    private fun tecla(texto: String, colunas: Int, acao: () -> Unit): View = TextView(this).apply {
        text = texto
        gravity = Gravity.CENTER
        textSize = if (texto.length > 1) 15f else 20f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(ContextCompat.getColorStateList(context, R.color.texto_botao_ficha))
        setBackgroundResource(R.drawable.item_painel)
        isFocusable = true
        setOnClickListener { acao() }
        val d = resources.displayMetrics.density
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = (36 * d).toInt()
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, colunas, colunas.toFloat())
            setMargins((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
        }
    }

    private fun ouvir() {
        runCatching {
            voz.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.busca_dica)))
        }
    }

    private fun focarResultados(): Boolean {
        val holder = resultados.findViewHolderForAdapterPosition(0) as? Inicio.Adapter.Holder
        val primeiraCapa = holder?.capas?.layoutManager?.findViewByPosition(0)
        if (primeiraCapa != null && primeiraCapa.requestFocus()) {
            return true
        }
        val foco = resultados.focusSearch(View.FOCUS_DOWN) ?: resultados.focusSearch(View.FOCUS_RIGHT)
        if (foco != null && foco !== resultados && estaDentroDe(foco, resultados)) {
            return foco.requestFocus()
        }
        return false
    }

    private fun estaDentroDe(vista: View, container: View): Boolean {
        var atual: View? = vista
        while (atual != null) {
            if (atual === container) return true
            atual = atual.parent as? View
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val foco = currentFocus
        if (event.action == KeyEvent.ACTION_DOWN && foco != null) {
            val grade = findViewById<GridLayout>(R.id.buscaTeclas)
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && estaDentroDe(foco, resultados)) {
                val lista = foco.parent as? RecyclerView
                    ?: resultados.findContainingViewHolder(foco)?.itemView?.findViewById(R.id.filaCapas)
                if (lista?.findContainingViewHolder(foco)?.bindingAdapterPosition == 0) {
                    return (ultimoFocoTeclado ?: grade.getChildAt(2)).requestFocus()
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && foco.parent === grade) {
                val vizinho = foco.focusSearch(View.FOCUS_RIGHT)
                if (vizinho?.parent !== grade) {
                    ultimoFocoTeclado = foco
                    return focarResultados() || super.dispatchKeyEvent(event)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun digitar(t: String) {
        if (digitado.length >= 40) return
        digitado.append(t)
        atualizar()
    }

    private fun apagar() {
        if (digitado.isNotEmpty()) digitado.deleteCharAt(digitado.length - 1)
        atualizar()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Teclado de verdade (USB, Bluetooth, o do celular): digita direto.
        val ch = event?.unicodeChar ?: 0
        if (ch > 0 && (Character.isLetterOrDigit(ch) || ch == ' '.code) &&
            keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
            digitar(ch.toChar().lowercase())
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) { apagar(); return true }
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) { ouvir(); return true }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Refaz os resultados.
     *
     * Espera a pessoa parar de digitar um instante — uma busca por letra em
     * cinquenta mil títulos travaria um TV Box — e cancela a anterior.
     */
    private fun atualizar(imediato: Boolean = false) {
        campo.text = digitado
        trabalho?.cancel()
        val termo = digitado.toString().trim()
        trabalho = lifecycleScope.launch(semDerrubar) {
            if (!imediato) delay(180)
            if (termo.isEmpty()) { mostrarInicio(); return@launch }
            val filas = mutableListOf<Inicio.Fila>()

            val alvoNorm = Vod.normalizar(termo)
            val canais = if (BuildConfig.TV_AO_VIVO) MinhasListas.somar(Unlock.channels(), Unlock.unlocked).filter { Vod.normalizar(it.name).contains(alvoNorm) }.take(20) else emptyList()
            if (canais.isNotEmpty()) filas += Inicio.Fila(getString(R.string.busca_canais), canais.map { c ->
                Inicio.Cartao(c.name, c.logo.orEmpty(), c.name.take(1).uppercase()) {
                    startActivity(Intent(this@BuscaActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, c.name))
                }
            }, Inicio.Tipo.LARGO)

            val achados = Vod.buscar(this@BuscaActivity, termo, if (termo.length == 1) 20 else 80)
            val filmes = achados.filter { !it.serie }
            val series = achados.filter { it.serie }
            if (filmes.isNotEmpty()) filas += Inicio.Fila(getString(R.string.vod_filmes), filmes.map { cartao(it) })
            if (series.isNotEmpty()) filas += Inicio.Fila(getString(R.string.vod_series), series.map { cartao(it) })

            if (filas.isNotEmpty()) mostrar(filas, termo)
            val colecoes = if (termo.length >= 2) Vod.buscarColecoes(this@BuscaActivity, termo).take(30) else emptyList()
            if (colecoes.isNotEmpty()) filas += Inicio.Fila(getString(R.string.busca_colecoes), colecoes.map { (tipo, c) ->
                val alvo = Alvo(c.titulo, true, "", c.ano, tipo, c.tmdbId.toIntOrNull() ?: 0)
                Inicio.Cartao(c.nomeCompleto, "", c.titulo.take(1).uppercase(), procurarCapa = true,
                    nomeDaCapa = c.titulo, alvo = alvo) { lembrar(); FichaActivity.abrir(this@BuscaActivity, alvo) }
            })

            mostrar(filas, termo)
            if (achados.isEmpty() && colecoes.isEmpty() && termo.length >= 3) Telemetria.buscouSemAchar("vod", termo)

            // Lançamentos e TMDB: busca novidades e lançamentos fora do catálogo local
            if (termo.length >= 3) {
                delay(300)
                val tmdbTitulos = Detalhes.buscarTitulos(termo)
                if (tmdbTitulos.isNotEmpty() && digitado.toString().trim() == termo) {
                    val relacionados = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        Generos.carregar(this@BuscaActivity)
                        tmdbTitulos.map { titulo ->
                            val local = Generos.titulo(titulo.tmdbId, titulo.serie) ?: titulo.titulo
                            titulo to Vod.achar(this@BuscaActivity, local, titulo.serie)
                        }
                    }
                    val disponiveis = relacionados.mapNotNull { it.second }.distinct().filterNot { it in achados }
                    if (disponiveis.isNotEmpty()) filas += Inicio.Fila("Outros títulos do catálogo", disponiveis.map(::cartao))
                    val descobertas = relacionados.filter { it.second == null }.map { (titulo, _) ->
                        val alvo = Alvo(titulo.titulo, titulo.serie, "", titulo.ano, tmdbId = titulo.tmdbId)
                        Inicio.Cartao(titulo.titulo, titulo.capa.orEmpty(), titulo.titulo.take(1), alvo = alvo) {
                            lembrar()
                            FichaActivity.abrir(this@BuscaActivity, alvo)
                        }
                    }
                    if (descobertas.isNotEmpty()) filas += Inicio.Fila("Descobertas", descobertas)
                    mostrar(filas, termo)
                }
            }

            // Atores vêm do TMDB: por último, e só com o termo parado.
            if (termo.length >= 3) {
                delay(200)
                val pessoas = Detalhes.pessoas(termo)
                if (pessoas.isNotEmpty() && digitado.toString().trim() == termo) {
                    filas += Inicio.Fila(getString(R.string.busca_atores), pessoas.map { p ->
                        Inicio.Cartao(p.nome, p.foto.orEmpty(), p.nome.take(1).uppercase()) {
                            lembrar(); AtorActivity.abrir(this@BuscaActivity, p)
                        }
                    })
                    mostrar(filas, termo)
                }
            }
        }
    }

    private fun cartao(a: Vod.Achado): Inicio.Cartao {
        val alvo = Alvo(a.titulo, a.serie, a.letra, a.ano)
        return Inicio.Cartao(a.nomeCompleto, "", a.titulo.take(1).uppercase(), procurarCapa = true,
            nomeDaCapa = a.nomeCompleto, alvo = alvo,
            progresso = if (a.serie) null else Progresso.fracao(this, Progresso.chaveFilme(a.titulo))) {
            lembrar(); FichaActivity.abrir(this, alvo)
        }
    }

    private fun lembrar() = Preferencias.lembrarBusca(digitado.toString())

    private fun mostrar(filas: List<Inicio.Fila>, termo: String) {
        adaptador.trocar(filas)
        estado.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        estado.text = if (Enderecos.temAlgum) getString(R.string.busca_nada, termo) else getString(R.string.vazio_inicio)
    }

    /** Sem nada digitado: as buscas recentes, a um OK. */
    private suspend fun mostrarInicio() {
        val filas = mutableListOf<Inicio.Fila>()
        val recentes = Preferencias.historicoBusca
        if (recentes.isNotEmpty()) filas += Inicio.Fila(getString(R.string.busca_recentes), recentes.map { t ->
            Inicio.Cartao(t, "", t.take(1).uppercase()) {
                digitado.clear(); digitado.append(t); atualizar(true)
            }
        }, Inicio.Tipo.LARGO)
        val destaque = Destaques.filas(this, somenteLocal = true).firstOrNull { it.itens.isNotEmpty() }
        if (destaque != null) filas += Inicio.Fila(destaque.titulo, destaque.itens.take(16).map { item ->
            val alvo = Alvo(item.titulo, item.serie, item.letra, item.ano,
                colecao = if (item.daColecao) item.colecao else "")
            Inicio.Cartao(item.titulo, item.capa, item.titulo.take(1), alvo = alvo) {
                FichaActivity.abrir(this, alvo)
            }
        })
        adaptador.trocar(filas)
        estado.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        estado.text = getString(R.string.busca_comece)
    }
}
