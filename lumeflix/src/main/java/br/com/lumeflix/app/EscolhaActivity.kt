package br.com.lumeflix.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tela inicial.
 *
 * Antes eram dois cartões — TV ou filmes — e só depois de escolher se via
 * alguma coisa. Agora é o que toda TV faz: um menu no topo, o título focado em
 * destaque com a imagem dele ao fundo, e fileiras — o que a pessoa estava
 * vendo, os canais ao vivo agora, os favoritos e as novidades. Tudo a um OK.
 *
 * Continua sendo a tela que o sistema abre (o nome da classe é o que o
 * launcher guarda), e é ela que atende os links `saimo://` da tela inicial da
 * TV e da busca por voz.
 */
@UnstableApi
class EscolhaActivity : TelaComMenu(), Connect.Listener {

    override val aba = Aba.INICIO
    override fun aoFocarMenu() { if (!temDestaque) cabecalhoPadrao() }
    override val aoMenuInicio: (() -> Unit) = { focarPrimeiro() }


    private lateinit var status: TextView
    private lateinit var filas: RecyclerView
    private lateinit var fundo: ImageView
    private lateinit var titulo: TextView
    private lateinit var meta: TextView
    private lateinit var sinopse: TextView
    private val atualizacao by lazy { OfertaDeAtualizacao(this) { mostrarStatus(it) } }
    private var jaIniciou = false
    private val handler = Handler(Looper.getMainLooper())
    private val hora = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    private var ultimoVoltar = 0L
    private var temDestaque = false
    private var cartaoDestaque: Inicio.Cartao? = null
    private var assistir: TextView? = null
    private var informacoes: TextView? = null

    private val adaptador by lazy {
        Inicio.Adapter(escopo = lifecycleScope, capaDe = { t, s -> Generos.capa(t, s) },
            paisagem = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.TV_AO_VIVO) {
            abrirTvAoVivo()
            return
        }
        setContentView(R.layout.activity_escolha)
        status = findViewById(R.id.escolhaStatus)
        filas = findViewById(R.id.inicioFilas)
        fundo = findViewById(R.id.inicioFundo)
        titulo = findViewById(R.id.inicioTitulo)
        meta = findViewById(R.id.inicioMeta)
        sinopse = findViewById(R.id.inicioSinopse)
        assistir = findViewById<TextView>(R.id.inicioAssistir)?.apply {
            Icones.inicio(this, R.drawable.ic_play)
            setOnClickListener {
                cartaoDestaque?.alvo?.let { PlayerActivity.abrir(this@EscolhaActivity, it) }
            }
            setOnKeyListener { _, codigo, evento ->
                if (evento.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (codigo) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { focarPrimeiro(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { MenuGlobal.focar(barraMenu, Aba.INICIO); true }
                    else -> false
                }
            }
        }

        informacoes = findViewById<TextView>(R.id.inicioInformacoes)?.apply {
            Icones.inicio(this, R.drawable.ic_info)
            setOnClickListener { cartaoDestaque?.alvo?.let { FichaActivity.abrir(this@EscolhaActivity, it) } }
            setOnKeyListener { _, codigo, evento ->
                if (evento.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (codigo) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { focarPrimeiro(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { MenuGlobal.focar(barraMenu, Aba.INICIO); true }
                    else -> false
                }
            }
        }
        assistir?.nextFocusRightId = R.id.inicioInformacoes
        informacoes?.nextFocusLeftId = R.id.inicioAssistir

        filas.layoutManager = Inicio.Filas(this)
        filas.adapter = adaptador
        filas.setItemViewCacheSize(4)
        cabecalhoPadrao()


        lifecycleScope.launch(semDerrubar) {
            // Ler 990 canais do disco leva segundos num TV Box fraco: fora da
            // linha da tela, para ela aparecer já.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (BuildConfig.TV_AO_VIVO && Remote.channels.isEmpty()) Remote.loadCached(this@EscolhaActivity)
            }
            montarFilas()
            
            // Primeira abertura (ou cache apagado): os endereços já estão ativos,
            // então baixa as listas sem a pessoa digitar nada.
            if (BuildConfig.TV_AO_VIVO && Remote.channels.isEmpty() && Enderecos.canais != null) {
                Listas.recarregar(this@EscolhaActivity.applicationContext)
                aoMudarListas()
            }
        }

        // A versão nova é oferecida aqui, antes de qualquer vídeo começar.
        filas.postDelayed({
            quandoAberturaAcabar { if (!isFinishing && Painel.dialogVisivel() == null) atualizacao.ofertar() }
        }, 2_500)
        InicializarCanais.agendar(this)

        val veioDeLink = intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEARCH
        atenderLink(intent)
        // "Ir direto ao último canal", escolhido em Ajustes: esta tela fica por
        // baixo, e VOLTAR do canal cai nela.
        if (BuildConfig.TV_AO_VIVO && savedInstanceState == null && !veioDeLink && Preferencias.abrirEm == "canal" &&
            Preferencias.ultimoCanal != null) {
            quandoAberturaAcabar { if (!isFinishing) startActivity(Intent(this, MainActivity::class.java)) }
        }
    }

    /** No APK de canais esta Activity é só a porta de entrada do launcher. */
    private fun abrirTvAoVivo() {
        lifecycleScope.launch(semDerrubar) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (Remote.channels.isEmpty()) Remote.loadCached(this@EscolhaActivity)
                if (Remote.channels.isEmpty() && Enderecos.canais != null) {
                    runCatching { Remote.refresh(this@EscolhaActivity.applicationContext, forcar = true) }
                    runCatching { GuiaTabelas.atualizar(this@EscolhaActivity.applicationContext) }
                }
            }
            val destino = Intent(this@EscolhaActivity, MainActivity::class.java)
            intent?.data?.takeIf { it.host == "canal" }?.getQueryParameter("n")
                ?.let { destino.putExtra(MainActivity.EXTRA_CANAL, it) }
            startActivity(destino)
            finish()
        }
    }

    override fun aoMudarListas() { lifecycleScope.launch(semDerrubar) { montarFilas() } }

    /** Roda já, ou assim que a abertura sair de cena. */
    private fun quandoAberturaAcabar(acao: () -> Unit) {
        acao()
    }

    private fun cabecalhoPadrao() {
        cartaoDestaque = null
        assistir?.visibility = View.GONE
        informacoes?.visibility = View.GONE
        painelDestaque.padrao(if (BuildConfig.TV_AO_VIVO) saudacao() else "Seu próximo favorito", getString(R.string.escolha_titulo))
        sinopse.text = if (BuildConfig.TV_AO_VIVO) getString(if (Vr.ativo) R.string.vr_inicio_dica else R.string.inicio_dica) else "Explore filmes e séries nas fileiras abaixo."
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("lumeflix.focoMenu", false)) MenuGlobal.focar(barraMenu, Aba.INICIO)
        atenderLink(intent)
    }

    override fun onStart() {
        super.onStart()
        if (jaIniciou) {
            atualizacao.retomarSePendente()
            // Voltando do player ou da ficha: o "continuar" mudou. O guia pode
            // ter saído da memória com o app em segundo plano.
            lifecycleScope.launch(semDerrubar) {
                if (BuildConfig.TV_AO_VIVO) Epg.carregarCache(this@EscolhaActivity)
                montarFilas()
            }
        }
        jaIniciou = true
    }

    override fun onResume() {
        super.onResume()
        Connect.listener = this
        Connect.notificarMudancaDeEstado()
    }

    override fun onPause() {
        super.onPause()
        if (Connect.listener === this) Connect.listener = null
    }

    override fun obterEstado(): org.json.JSONObject = org.json.JSONObject().apply {
        put("t", "state")
        put("mode", "idle")
        put("screen", "home")
        put("channel", "")
        put("playing", false)
        put("multi", false)
        put("active", 0)
        put("screens", org.json.JSONArray())
    }

    override fun sintonizarCanal(nome: String, metade: Int?) {
        if (!BuildConfig.TV_AO_VIVO) return
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, nome))
    }

    override fun simularTecla(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val focado = currentFocus
                if (focado == null || !filas.hasFocus()) {
                    MenuGlobal.focar(barraMenu, Aba.INICIO)
                    return true
                }
                val proximo = focado.focusSearch(View.FOCUS_UP)
                if (proximo != null && proximo !== focado) {
                    proximo.requestFocus(View.FOCUS_UP)
                    return true
                }
                MenuGlobal.focar(barraMenu, Aba.INICIO)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val focado = currentFocus
                if (focado == null || MenuGlobal.temFoco(barraMenu)) {
                    if (assistir?.visibility == View.VISIBLE) assistir?.requestFocus() else focarPrimeiro()
                    return true
                }
                val proximo = focado.focusSearch(View.FOCUS_DOWN)
                if (proximo != null && proximo !== focado) {
                    proximo.requestFocus(View.FOCUS_DOWN)
                    return true
                }
                filas.smoothScrollBy(0, 250)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val focado = currentFocus ?: return false
                val proximo = focado.focusSearch(View.FOCUS_LEFT)
                if (proximo != null && proximo !== focado) {
                    proximo.requestFocus(View.FOCUS_LEFT)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val focado = currentFocus ?: return false
                val proximo = focado.focusSearch(View.FOCUS_RIGHT)
                if (proximo != null && proximo !== focado) {
                    proximo.requestFocus(View.FOCUS_RIGHT)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val focado = currentFocus
                if (focado != null) {
                    focado.performClick()
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                val now = android.os.SystemClock.uptimeMillis()
                val event = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
                onKeyDown(keyCode, event)
                return true
            }
        }
        val now = android.os.SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        dispatchKeyEvent(KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0))
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun abrirAoVivo() {
        if (!BuildConfig.TV_AO_VIVO) return
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun focarPrimeiro(tentativas: Int = 10) {
        filas.scrollToPosition(0)
        filas.post {
            val fileira = filas.findViewHolderForAdapterPosition(0)?.itemView
            val capas = fileira?.findViewById<RecyclerView>(R.id.filaCapas)
            if (capas?.getChildAt(0)?.requestFocus() == true) return@post
            // A fileira ainda não desenhou as capas: tenta de novo no próximo
            // quadro, em vez de largar o foco no menu.
            if (tentativas > 0) filas.postDelayed({ focarPrimeiro(tentativas - 1) }, 60)
            else if (vazioVisivel()) focarBotaoDoVazio()
            else MenuGlobal.focar(barraMenu, Aba.INICIO)
        }
    }

    // MARK: - Fileiras

    private var primeiraVez = true
    private val abertaEm = System.currentTimeMillis()

    private suspend fun montarFilas() {
        if (BuildConfig.TV_AO_VIVO) {
            mostrarFilas(listOfNotNull(aoVivo()))
            return
        }
        // O que é local (continuar, ao vivo, favoritos) entra antes das fichas:
        // ler as fichas (3,7 MB) leva até 2 s num TV Box lento, e a tela
        // ficava vazia esse tempo todo. As capas dos destaques chegam depois.
        val salvas = if (primeiraVez) lerFileiras() else emptyList()
        if (salvas.isNotEmpty()) mostrarFilas(listOfNotNull(continuar(), favoritos()) + salvas)
        if (salvas.isEmpty() && !Generos.prontos) {
            val locais = listOfNotNull(continuar(), favoritos()) + Destaques.filas(this, somenteLocal = true).filterNot { Destaques.lancamentoGenerico(it.titulo) }.map { fila ->
                Inicio.Fila(fila.titulo, fila.itens.map { cartaoDe(it) })
            }
            if (locais.isNotEmpty()) mostrarFilas(locais)

        }
        val lista = mutableListOf<Inicio.Fila>()
        continuar()?.let { lista += it }
        aoVivo()?.let { lista += it }
        favoritos()?.let { lista += it }
        for (fila in Destaques.filas(this).filterNot { Destaques.lancamentoGenerico(it.titulo) }) {
            val cartoes = fila.itens.map { cartaoDe(it) }
            if (cartoes.isNotEmpty()) lista += Inicio.Fila(fila.titulo, cartoes)
        }
        if (salvas.isEmpty()) mostrarFilas(lista)
        Generos.carregar(this)
        val generosLocais = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val entradas = Vod.entradas(this@EscolhaActivity)
            listOf("Ação", "Comédia", "Drama", "Terror", "Animação", "Ficção científica").mapNotNull { genero ->
                val titulos = entradas.asSequence().map { it.achado }.filter { Generos.tem(it.nomeCompleto, it.serie, genero) }.take(60).toList()
                if (titulos.isEmpty()) null else Inicio.Fila(genero, titulos.map { a ->
                    cartaoDe(Destaques.Item(a.titulo, if (a.serie) 's' else 'f', a.letra, a.ano, Generos.capa(a.nomeCompleto, a.serie).orEmpty()))
                })
            }
        }
        lista.addAll(generosLocais)
        if (salvas.isEmpty() && generosLocais.isNotEmpty()) mostrarFilas(lista)
        val grupos = linkedMapOf<String, MutableList<Vod.Achado>>()
        for (serie in listOf(false, true)) {
            for ((nome, titulos) in Detalhes.fileirasEditoriais(this, serie)) {
                val secao = if (nome == "Em alta hoje") (if (serie) "Top 10 séries em alta hoje" else "Top 10 filmes em alta hoje") else nome
                grupos.getOrPut(secao) { mutableListOf() }.addAll(titulos)
            }
        }
        val topo = grupos.map { (nome, titulos) ->
            Inicio.Fila(nome, titulos.map { a ->
                cartaoDe(Destaques.Item(a.titulo, if (a.serie) 's' else 'f', a.letra, a.ano,
                    Generos.capa(a.nomeCompleto, a.serie).orEmpty()))
            })
        }
        val completas = topo + lista.filterNot { it.titulo in grupos } + Addons.filas(this)
        if (completas.isNotEmpty()) {
            mostrarFilas(completas)
            sugerirPorHistorico(this)?.let { mostrarFilas(completas + it) }
            if (topo.isNotEmpty()) guardarFileiras(Inicio.organizarCatalogo(completas, getString(R.string.vod_continuar)))
        }
    }

    private val cacheInicio by lazy { getSharedPreferences("inicio_recente", MODE_PRIVATE) }

    private fun guardarFileiras(filas: List<Inicio.Fila>) {
        val dados = org.json.JSONArray()
        for (fila in filas.filterNot { it.titulo in setOf(getString(R.string.vod_continuar), getString(R.string.vod_favoritos)) }.take(5)) {
            val itens = org.json.JSONArray()
            for (c in fila.cartoes.take(12)) {
                val a = c.alvo ?: continue
                if (Addons.ref(a) != null) continue
                val tipo = when (a.colecao) { "animes" -> "a"; "doramas" -> "d"; else -> if (a.serie) "s" else "f" }
                itens.put(org.json.JSONArray(listOf(a.titulo, tipo, a.letra, a.ano, c.capa, c.trailer.orEmpty(), c.capaLarga.orEmpty())))
            }
            if (itens.length() > 0) dados.put(org.json.JSONArray().put(fila.titulo).put(itens))
        }
        if (dados.length() > 0) cacheInicio.edit().putString("filas", dados.toString()).apply()
    }

    private fun lerFileiras(): List<Inicio.Fila> = runCatching {
        val dados = org.json.JSONArray(cacheInicio.getString("filas", "[]"))
        (0 until dados.length()).map { i ->
            val fila = dados.getJSONArray(i)
            val itens = fila.getJSONArray(1)
            Inicio.Fila(fila.getString(0), (0 until itens.length()).map { j ->
                val c = itens.getJSONArray(j)
                cartaoDe(Destaques.Item(c.getString(0), c.getString(1).single(), c.getString(2),
                    c.getString(3), c.getString(4), c.getString(5))).apply { capaLarga = c.optString(6).ifBlank { null } }
            })
        }
    }.getOrDefault(emptyList())

    private fun mostrarFilas(filasRecebidas: List<Inicio.Fila>) {
        val lista = Inicio.organizarCatalogo(filasRecebidas, getString(R.string.vod_continuar))
        if (isFinishing || isDestroyed) return
        if (lista.isEmpty()) {
            mostrarVazio(getString(if (Enderecos.temAlgum || MinhasListas.todas().isNotEmpty()) R.string.vazio_falhou else R.string.vazio_inicio))
            cabecalhoPadrao()
            // Atrás da abertura animada o foco não chega; quando ela sai, vai ao botão.
            quandoAberturaAcabar { focarBotaoDoVazio() }
        } else esconderVazio()
        // Refazer as fileiras recria as capas e o foco escaparia para o menu.
        // Nos primeiros segundos (a pessoa ainda não mexeu), ele volta à
        // primeira capa; depois disso, fica onde a pessoa deixou.
        val devolver = primeiraVez
        adaptador.trocar(lista)
        carrossel.atualizar(lista)
        if (devolver) {
            primeiraVez = false
            filas.post { if (!MenuGlobal.temFoco(barraMenu)) focarPrimeiro() }
        }
    }

    private fun continuar(): Inicio.Fila? {
        val itens = Progresso.emAndamento(this).take(20)
        if (itens.isEmpty()) return null
        return Inicio.Fila(getString(R.string.vod_continuar), itens.map { a ->
            val alvo = a.endereco?.alvo
            Inicio.Cartao(
                titulo = a.rotulo,
                capa = "",
                inicial = a.titulo.take(1).uppercase(),
                progresso = a.fracao,
                procurarCapa = a.serie,
                nomeDaCapa = a.titulo,
                alvo = alvo,
                aoMenu = {
                    Painel.mostrar(this, a.rotulo, listOfNotNull(
                        alvo?.let { Painel.Item(getString(R.string.inicio_abrir_ficha), icone = R.drawable.ic_info) { FichaActivity.abrir(this, it) } },
                        Painel.Item(getString(R.string.inicio_remover_continuar)) {
                            Progresso.esquecer(this, a)
                            lifecycleScope.launch(semDerrubar) { montarFilas() }
                        }))
                },
            ) {
                if (alvo != null) {
                    val (t, e) = if (a.terminado) a.temporada to a.episodio + 1 else a.temporada to a.episodio
                    PlayerActivity.abrir(this, alvo, t, e)
                } else {
                    BuscaActivity.abrir(this, Generos.semAno(a.titulo))
                }
            }
        })
    }

    /** Os canais favoritos — ou os abertos — com o que passa agora. */
    private fun aoVivo(): Inicio.Fila? {
        if (!BuildConfig.TV_AO_VIVO) return null
        val todos = MinhasListas.somar(Remote.channels, false)
        if (todos.isEmpty()) return null
        val ultimo = todos.firstOrNull { it.name == Preferencias.ultimoCanal }
        val favoritos = todos.filter { Favorites.contains(it.name) }
        val escolhidos = (listOfNotNull(ultimo) + favoritos.ifEmpty {
            // Sem favoritos: a TV aberta; e, se a lista não tem (as listas da
            // própria pessoa), os primeiros canais dela.
            todos.filter { Categorias.de(it) == "TV Aberta" }.ifEmpty { todos }
        }).distinct().take(16)
        val agora = System.currentTimeMillis()
        return Inicio.Fila(getString(R.string.inicio_ao_vivo_agora), escolhidos.map { canal ->
            val programa = Epg.nowNext(canal.name, agora)?.first
            Inicio.Cartao(
                titulo = canal.name,
                capa = canal.logo.orEmpty(),
                inicial = canal.name.take(1).uppercase(),
                progresso = programa?.progress(agora),
                subtitulo = programa?.title,
            ) {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, canal.name))
            }
        }, Inicio.Tipo.LARGO)
    }

    private fun favoritos(): Inicio.Fila? {
        val itens = VodFavoritos.lista(this)
        if (itens.isEmpty()) return null
        return Inicio.Fila(getString(R.string.vod_favoritos), itens.map { item ->
            val alvo = Alvo(item.titulo, item.serie, item.letra, item.ano)
            Inicio.Cartao(
                titulo = item.nomeCompleto, capa = "", inicial = item.titulo.take(1).uppercase(),
                procurarCapa = item.serie, alvo = alvo,
                aoMenu = { alternarFavorito(alvo) },
            ) { FichaActivity.abrir(this, alvo) }
        })
    }

    private fun cartaoDe(item: Destaques.Item): Inicio.Cartao {
        val alvo = Alvo(item.titulo, item.serie, item.letra, item.ano,
            colecao = if (item.daColecao) item.colecao else "")
        return Inicio.Cartao(
            titulo = item.titulo, capa = item.capa, inicial = item.titulo.take(1).uppercase(), trailer = item.trailer,
            progresso = if (item.serie) null else Progresso.fracao(this, Progresso.chaveFilme(item.titulo)),
            alvo = alvo,
            aoMenu = if (item.daColecao) null else ({ alternarFavorito(alvo) }),
        ) { FichaActivity.abrir(this, alvo) }
    }

    private fun alternarFavorito(alvo: Alvo) {
        val marcado = VodFavoritos.alternar(this, VodFavoritos.Item(alvo.titulo, alvo.serie, alvo.letra, alvo.ano))
        Toast.makeText(this, if (marcado) R.string.vod_ficha_favorito else R.string.inicio_desfavoritar,
            Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(semDerrubar) { montarFilas() }
    }

    // MARK: - Destaque

    private val painelDestaque by lazy { Destaque(this, lifecycleScope, fundo, titulo, meta, sinopse) { it.aoEscolher() } }

    private val carrossel by lazy { CarrosselInicio(this, painelDestaque, ::mostrarDestaque) }

    private fun mostrarDestaque(cartao: Inicio.Cartao) {
        temDestaque = true
        cartaoDestaque = cartao
        assistir?.visibility = if (cartao.alvo != null) View.VISIBLE else View.GONE
        informacoes?.visibility = assistir?.visibility ?: View.GONE
        painelDestaque.mostrar(cartao)
    }

    // MARK: - Teclas

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Durante a abertura qualquer tecla pula, e nenhuma chega às fileiras.
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_TV -> { abrirAoVivo(); return true }
            KeyEvent.KEYCODE_SEARCH -> { if (!BuildConfig.TV_AO_VIVO) BuscaActivity.abrir(this); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                // Primeiro VOLTAR sobe para o menu; o segundo, de lá, pede
                // confirmação — sair do app sem querer é o erro mais comum.
                if (!MenuGlobal.temFoco(barraMenu)) {
                    filas.scrollToPosition(0)
                    MenuGlobal.focar(barraMenu, Aba.INICIO)
                    return true
                }
                val agora = System.currentTimeMillis()
                if (agora - ultimoVoltar > 2_500) {
                    ultimoVoltar = agora
                    Toast.makeText(this, R.string.inicio_sair, Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Link vindo de fora do app: o "Continuar assistindo" e a fileira do
     * Saimo na tela inicial, e a busca por voz do sistema.
     */
    private fun atenderLink(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                if (intent.data?.host == "inicio") return
                Links.abrir(this, intent.data)
                intent.data = null
            }
            Intent.ACTION_SEARCH -> {
                val termo = intent.getStringExtra(android.app.SearchManager.QUERY)?.trim().orEmpty()
                if (!BuildConfig.TV_AO_VIVO && termo.isNotEmpty()) BuscaActivity.abrir(this, termo)
                intent.action = null
            }
        }
    }

    /** "Boa noite" às dez da noite: a abertura fala como gente. */
    private fun saudacao(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return getString(when (h) {
            in 5..11 -> R.string.escolha_bom_dia
            in 12..17 -> R.string.escolha_boa_tarde
            else -> R.string.escolha_boa_noite
        })
    }

    private fun mostrarStatus(texto: String) {
        status.text = texto
        status.visibility = View.VISIBLE
    }

    companion object {
        /** Volta à tela inicial, fechando o que estiver por cima dela. */
        fun voltar(context: Context) = context.startActivity(Intent(context, EscolhaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
