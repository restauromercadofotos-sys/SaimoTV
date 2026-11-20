package br.com.sintonia.tv

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@UnstableApi
class EventosActivity : TelaComEstadoVazio() {

    data class Jogo(
        val casa: String,
        val fora: String,
        val casaLogo: String,
        val foraLogo: String,
        val liga: String,
        val ligaLogo: String,
        val inicio: Long,
        val fim: Long,
        val canal: Channel?,
        /// "pre", "in" ou "post", quando a ESPN ou a agenda do monitor conhece o jogo.
        val estado: String = "",
        val placar: String = "",
        val tempo: String = "",
        val nomeEvento: String = "",
    ) {
        val titulo get() = nomeEvento.ifBlank { "$casa x $fora" }
        fun aoVivo(agora: Long) = estado == "in" || (estado.isEmpty() && agora in inicio until fim)
        fun acabou(agora: Long) = estado == "post" || (estado != "in" && agora >= fim)
    }

    private lateinit var filas: RecyclerView
    private lateinit var destaque: Destaque
    private val adaptador by lazy { Inicio.Adapter(aoFocar = { destaque.mostrar(it) }) }
    private var montou = false
    private var atualizando: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_escolha)
        filas = findViewById(R.id.inicioFilas)
        filas.layoutManager = Inicio.Filas(this)
        filas.adapter = adaptador
        destaque = Destaque(this, findViewById<ImageView>(R.id.inicioFundo),
            findViewById(R.id.inicioTitulo), findViewById(R.id.inicioMeta), findViewById(R.id.inicioSinopse))
        destaque.padrao(getString(R.string.menu_eventos), getString(R.string.carregando_catalogo))
    }

    override fun onStart() {
        super.onStart()
        // O placar anda: enquanto a tela está aberta, busca de novo a cada minuto
        // Fora da tela (onStop) nada é buscado.
        atualizando = lifecycleScope.launch(semDerrubar) {
            // A tela não depende do Ao vivo ter sido aberto: sem canais na
            // memória (app reaberto direto aqui, TV Box ainda lendo o disco),
            // lê a lista guardada; sem ela, baixa.
            withContext(Dispatchers.IO) {
                runCatching {
                    if (Remote.channels.isEmpty()) Remote.loadCached(this@EventosActivity)
                    if (Remote.channels.isEmpty()) Remote.refresh(this@EventosActivity)
                }
            }
            launch { if (Epg.channelsWithGuide == 0) Epg.load(this@EventosActivity) {} }
            esconderVazio()
            while (isActive) {
                val jogos = withContext(Dispatchers.IO) { runCatching { buscar() }.getOrNull() }
                if (jogos == null && !montou) {
                    destaque.padrao(getString(R.string.menu_eventos), getString(R.string.eventos_erro))
                } else if (jogos != null) mostrar(jogos)
                // Sem nada na tela, tenta de novo logo; com jogos, a cada minuto.
                delay(if (montou) 60_000 else 15_000)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        atualizando?.cancel()
    }

    // MARK: - Tela

    private fun mostrar(jogos: List<Jogo>) {
        if (isFinishing || isDestroyed) return
        val agora = System.currentTimeMillis()
        val hoje = diaDe(agora)
        val vivos = jogos.filter { !it.acabou(agora) }.sortedBy { it.inicio }
        val grupos = linkedMapOf<Int, MutableList<Jogo>>()
        for (j in vivos) {
            val grupo = when {
                j.aoVivo(agora) -> R.string.eventos_ao_vivo
                j.inicio - agora <= 2 * 3_600_000L -> R.string.eventos_em_breve
                diaDe(j.inicio) == hoje -> R.string.eventos_hoje
                diaDe(j.inicio) == hoje + 1 -> R.string.eventos_amanha
                else -> R.string.eventos_proximos
            }
            grupos.getOrPut(grupo) { mutableListOf() } += j
        }
        val ordem = listOf(R.string.eventos_ao_vivo, R.string.eventos_em_breve, R.string.eventos_hoje,
            R.string.eventos_amanha, R.string.eventos_proximos)
        val lista = ordem.mapNotNull { g ->
            grupos[g]?.let { Inicio.Fila(getString(g), it.map { j -> cartao(j, agora) }, Inicio.Tipo.EVENTO) }
        }
        if (lista.isEmpty()) {
            destaque.padrao(getString(R.string.menu_eventos), getString(R.string.eventos_nenhum))
            adaptador.trocar(emptyList())
            return
        }
        if (!montou) {
            val aoVivo = vivos.count { it.aoVivo(agora) }
            destaque.padrao(getString(R.string.menu_eventos),
                resources.getQuantityString(R.plurals.eventos_resumo, vivos.size, vivos.size, aoVivo))
        }
        adaptador.trocar(lista)
        if (!montou) {
            montou = true
            focarQuandoPronto {
                filas.findViewHolderForAdapterPosition(0)?.itemView
                    ?.findViewById<RecyclerView>(R.id.filaCapas)?.getChildAt(0)
            }
        }
    }

    private fun cartao(j: Jogo, agora: Long): Inicio.Cartao {
        val aoVivo = j.aoVivo(agora)
        val quando = quando(j.inicio, agora)
        val rotuloNoAr = if (j.estado.isEmpty()) "No ar" else getString(R.string.radio_ao_vivo)
        val selo = if (aoVivo) listOf(rotuloNoAr, j.placar).filter { it.isNotBlank() }.joinToString("  ")
                   else quando
        val meta = listOf(j.liga,
            if (aoVivo) listOf(rotuloNoAr, j.placar, j.tempo).filter { it.isNotBlank() }.joinToString(" ")
            else quando,
        ).filter { it.isNotBlank() }.joinToString("  ·  ")
        return Inicio.Cartao(
            titulo = j.titulo, capa = "", inicial = j.casa.take(1),
            subtitulo = listOf(j.canal?.name ?: "Sem canal", j.liga).filter { it.isNotBlank() }.joinToString(" · "),
            colagem = listOf(j.casaLogo, j.foraLogo, j.ligaLogo),
            selo = selo, seloAoVivo = aoVivo,
            meta = meta,
            sinopse = if (j.canal != null) getString(R.string.eventos_dica, j.canal.name) else "Sem canal",
        ) {
            if (j.canal == null) android.widget.Toast.makeText(this, "Sem canal", android.widget.Toast.LENGTH_SHORT).show()
            else startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, j.canal.name))
        }
    }

    private val hora = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val semana = SimpleDateFormat("EEE", Locale.forLanguageTag("pt-BR"))

    /** "21:30", "amanhã 16:00" ou "sáb 16:00". */
    private fun quando(inicio: Long, agora: Long): String {
        val d = diaDe(inicio) - diaDe(agora)
        val h = hora.format(Date(inicio))
        return when {
            d <= 0 -> h
            d == 1 -> getString(R.string.eventos_amanha_as, h)
            else -> "${semana.format(Date(inicio)).trimEnd('.')} $h"
        }
    }

    private fun diaDe(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
    }

    // MARK: - Dados

    /**
     * Como no Mac: o DNS do aparelho primeiro e o DNS-over-HTTPS dos canais só
     * quando ele não acha o nome. Antes era o contrário, e numa rede em que o
     * 1.1.1.1 demora a espera dele comia os 8 s do pedido: a agenda nunca
     * chegava e a tela ficava sem jogos, enquanto o Mac mostrava.
     */
    private val clienteEventos by lazy {
        val reserva = Playback.client.dns
        Playback.client.newBuilder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    runCatching { okhttp3.Dns.SYSTEM.lookup(hostname) }.getOrNull()?.takeIf { it.isNotEmpty() }
                        ?: reserva.lookup(hostname)
            })
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun texto(url: String): String? = runCatching {
        clienteEventos.newCall(Request.Builder().url(url).header("User-Agent", Playback.DEFAULT_USER_AGENT).build())
            .execute().use { r -> if (r.isSuccessful) r.body.string() else null }
    }.getOrNull()

    private suspend fun buscar(): List<Jogo>? {
        val respostas = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
        val formato = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
        val ate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }
        val desde = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val periodo = "${formato.format(desde.time)}-${formato.format(ate.time)}"
        val pedidos = ESPN.map { liga ->
            ESCOPO_ESPN.async {
                texto("https://site.api.espn.com/apis/site/v2/sports/$liga/scoreboard?dates=$periodo")
                    ?.let { respostas += liga to it }
            }
        }
        kotlinx.coroutines.withTimeoutOrNull(6_000) { pedidos.awaitAll() }
        val recebidas = respostas.toList()
        val canais = MinhasListas.canaisDaTela()
        val porNome = canais.associateBy { chaveCanal(it.name) }
        val agora = System.currentTimeMillis()
        val daGrade = canais.flatMap { canal ->
            Epg.schedule(canal.name).mapNotNull { programa ->
                val esporte = modalidadeDaGrade(programa, canal) ?: return@mapNotNull null
                if (programa.stop <= agora || programa.start > ate.timeInMillis) return@mapNotNull null
                Jogo("", "", "", "", esporte, "", programa.start, programa.stop, canal, nomeEvento = programa.title)
            }
        }
        if (recebidas.isEmpty() && daGrade.isEmpty()) return null
        val daAgenda = recebidas.flatMap { (liga, corpo) -> lerEspn(corpo, porNome, liga == "racing/f1") }
        return (daGrade + daAgenda).distinctBy { Triple(it.titulo, it.inicio, it.canal?.name) }
            .filter { evento -> evento.canal != null || daGrade.none { it.titulo == evento.titulo && it.inicio == evento.inicio } }
    }

    private fun chaveCanal(nome: String): String = nome.trim().lowercase(Locale.ROOT)
        .replace(Regex(" *(?:fhd|uhd|hd|sd|4k)$"), "").trimEnd()

    private fun lerEspn(corpo: String, canais: Map<String, Channel>, formula1: Boolean): List<Jogo> = runCatching {
        val json = JSONObject(corpo)
        val liga = json.optJSONArray("leagues")?.optJSONObject(0)
        val ligaLogo = liga?.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
        val eventos = json.optJSONArray("events") ?: return@runCatching emptyList()

        if (formula1) return@runCatching (0 until eventos.length()).flatMap { i ->
            val evento = eventos.getJSONObject(i)
            val sessoes = evento.optJSONArray("competitions")
            (0 until (sessoes?.length() ?: 0)).mapNotNull { j ->
                val sessao = sessoes?.optJSONObject(j) ?: return@mapNotNull null
                val inicio = instanteEspn(sessao.optString("date")) ?: return@mapNotNull null
                val tipo = sessao.optJSONObject("type")?.optString("abbreviation").orEmpty()
                val rotulo = when (tipo) {
                    "FP1" -> "Treino livre 1"
                    "FP2" -> "Treino livre 2"
                    "FP3" -> "Treino livre 3"
                    "Qual" -> "Classificação"
                    "Race" -> "Corrida"
                    "Sprint" -> "Sprint"
                    "SprintQual" -> "Classificação da sprint"
                    else -> tipo.ifBlank { return@mapNotNull null }
                }
                val estado = sessao.optJSONObject("status")?.optJSONObject("type")?.optString("state").orEmpty()
                Jogo("", "", "", "", "Fórmula 1", ligaLogo, inicio, inicio + 2 * 3_600_000L,
                    canalDaCompeticao(sessao, canais), estado = estado, nomeEvento = "${evento.optString("name")} · $rotulo")
            }
        }
        (0 until eventos.length()).mapNotNull { i ->
            runCatching {
                val ev = eventos.getJSONObject(i)
                val competicao = ev.optJSONArray("competitions")?.optJSONObject(0) ?: return@runCatching null
                val comps = competicao.optJSONArray("competitors") ?: return@runCatching null
                var casa: JSONObject? = null; var fora: JSONObject? = null
                for (c in 0 until comps.length()) {
                    val comp = comps.getJSONObject(c)
                    if (comp.optString("homeAway") == "home") casa = comp else fora = comp
                }
                val ca = casa ?: return@runCatching null; val fo = fora ?: return@runCatching null
                val status = ev.optJSONObject("status")?.optJSONObject("type")
                val estado = status?.optString("state").orEmpty()
                val inicio = instanteEspn(ev.optString("date")) ?: return@runCatching null
                val canal = canalDaCompeticao(competicao, canais)
                Jogo(
                    ca.getJSONObject("team").optString("name"), fo.getJSONObject("team").optString("name"),
                    ca.getJSONObject("team").optString("logo"), fo.getJSONObject("team").optString("logo"),
                    liga?.optString("name").orEmpty(), ligaLogo, inicio, inicio + 2 * 3_600_000L, canal,
                    estado, if (estado == "pre") "" else "${ca.optString("score")} x ${fo.optString("score")}",
                    if (estado == "in") status?.optString("shortDetail").orEmpty() else "",
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun instanteEspn(data: String): Long? =
        runCatching {
            val padrao = if (data.length == 17) "yyyy-MM-dd'T'HH:mm'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'"
            SimpleDateFormat(padrao, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(data)?.time
        }.getOrNull()

    private fun canalDaCompeticao(competicao: JSONObject, canais: Map<String, Channel>): Channel? {
        val emissoras = competicao.optJSONArray("broadcasts")
        return (0 until (emissoras?.length() ?: 0)).asSequence()
            .mapNotNull { emissoras?.optJSONObject(it)?.optJSONArray("names") }
            .flatMap { nomes -> (0 until nomes.length()).asSequence().map { nomes.optString(it) } }
            .mapNotNull { canais[chaveCanal(it)] }.firstOrNull()
    }

    private companion object {
        val ESCOPO_ESPN = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val ESPN = listOf(
            "soccer/bra.1", "soccer/bra.2", "soccer/bra.copa_do_brazil", "soccer/conmebol.libertadores",
            "soccer/conmebol.sudamericana", "soccer/uefa.champions", "soccer/uefa.europa", "soccer/eng.1",
            "soccer/esp.1", "soccer/ita.1", "soccer/ger.1", "soccer/fra.1", "soccer/por.1", "soccer/ned.1",
            "soccer/arg.1", "soccer/ksa.1", "soccer/fifa.world", "soccer/fifa.worldq.conmebol",
            "basketball/nba", "basketball/wnba", "racing/f1", "football/nfl", "baseball/mlb", "hockey/nhl", "mma/ufc",
        )
    }
}


private val esportesNaGrade = listOf(
    Regex("""\b(?:f[oó]rmula ?1|f1)\b""", RegexOption.IGNORE_CASE) to "Fórmula 1",
    Regex("""\b(?:v[oô]lei(?:bol)?|volleyball)\b""", RegexOption.IGNORE_CASE) to "Vôlei",
    Regex("""\b(?:basquete(?:bol)?|basketball|wnba|nba|nbb)\b""", RegexOption.IGNORE_CASE) to "Basquete",
    Regex("""\b(?:futebol|soccer|brasileir[aã]o|libertadores)\b""", RegexOption.IGNORE_CASE) to "Futebol",
)

internal fun modalidadeDaGrade(programa: Programme, canal: Channel): String? {
    val texto = "${programa.title} ${programa.category}"
    return esportesNaGrade.firstOrNull { (padrao, _) -> padrao.containsMatchIn(texto) }?.second
        ?: "Esportes".takeIf {
            Categorias.de(canal).equals("Esportes", ignoreCase = true) &&
                Regex("""\s(?:x|vs\.?)\s""", RegexOption.IGNORE_CASE).containsMatchIn(programa.title)
        }
}
