package br.com.sintonia.tv

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class EscolhaActivity : TelaComMenu() {
    override val aba = Aba.INICIO
    private lateinit var destaque: Destaque
    private lateinit var filas: RecyclerView
    private val adaptador by lazy { Inicio.Adapter(aoFocar = { destaque.mostrar(it) }) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_escolha)
        destaque = Destaque(this, findViewById(R.id.inicioFundo), findViewById(R.id.inicioTitulo),
            findViewById(R.id.inicioMeta), findViewById(R.id.inicioSinopse))
        destaque.padrao(getString(R.string.app_name), getString(R.string.menu_ao_vivo))
        filas = findViewById(R.id.inicioFilas)
        filas.layoutManager = Inicio.Filas(this)
        filas.adapter = adaptador
        lifecycleScope.launch(semDerrubar) {
            withContext(Dispatchers.IO) {
                if (Remote.channels.isEmpty() && Enderecos.canais != null) {
                    Remote.loadCached(this@EscolhaActivity)
                    if (Remote.channels.isEmpty()) Listas.recarregar(applicationContext)
                }
            }
            Epg.carregarCache(this@EscolhaActivity)
            montar()
            if (savedInstanceState == null && !atender(intent) &&
                intent.action == Intent.ACTION_MAIN && Preferencias.abrirEm == "canal") abrirCanal()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::filas.isInitialized) montar()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        atender(intent)
    }

    override fun aoMudarListas() = montar()

    private fun atender(intent: Intent): Boolean {
        if (intent.getBooleanExtra(EXTRA_BUSCAR, false)) {
            intent.removeExtra(EXTRA_BUSCAR)
            onSearchRequested()
            return true
        }
        return when (intent.action) {
            Intent.ACTION_VIEW -> Links.abrir(this, intent.data)
            Intent.ACTION_SEARCH -> {
                val termo = intent.getStringExtra(SearchManager.QUERY).orEmpty()
                val canais = buscarCanais(termo)
                if (canais.isNotEmpty()) Painel.mostrar(this, getString(R.string.menu_buscar),
                    canais.map { canal -> Painel.Item(canal.name) { abrirCanal(canal.name) } })
                else android.widget.Toast.makeText(this, getString(R.string.busca_sem_canal, termo), android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            else -> false
        }
    }

    private fun montar() {
        Favorites.load(this)
        val canais = MinhasListas.canaisDaTela()
        if (canais.isEmpty()) {
            mostrarVazio(getString(R.string.vazio_canais))
            adaptador.trocar(emptyList())
            return
        }
        esconderVazio()
        val ordem = Categorias.ordenar(canais)
        val recentes = ordem.firstOrNull { it.name == Preferencias.ultimoCanal }
        val grupos = listOf(
            getString(R.string.menu_ao_vivo) to (listOfNotNull(recentes) + ordem).distinct().take(24),
            Categorias.FAVORITOS to ordem.filter { Favorites.contains(it.name) },
        )
        adaptador.trocar(grupos.filter { it.second.isNotEmpty() }.map { (nome, grupo) ->
            Inicio.Fila(nome, grupo.map { canal ->
                val programa = Epg.nowNext(canal.name)?.first
                Inicio.Cartao(canal.name, canal.logo.orEmpty(), canal.name.take(1).uppercase(),
                    progresso = programa?.progress(System.currentTimeMillis()), subtitulo = programa?.title) {
                    abrirCanal(canal.name)
                }
            })
        })
    }

    private fun abrirCanal(nome: String? = null) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, nome))
    }

    companion object {
        const val EXTRA_BUSCAR = "br.com.sintonia.tv.BUSCAR"

        fun voltar(context: Context) = context.startActivity(Intent(context, EscolhaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

internal fun buscarCanais(termo: String): List<Channel> {
    val busca = termo.trim()
    if (busca.isEmpty()) return emptyList()
    return MinhasListas.canaisDaTela().filter { it.name.contains(busca, ignoreCase = true) }.take(40)
}
