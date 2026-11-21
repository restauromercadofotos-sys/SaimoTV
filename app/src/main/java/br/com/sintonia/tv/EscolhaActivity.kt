package br.com.sintonia.tv

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Entrada do launcher e da busca do sistema. O player é a tela do aplicativo. */
@UnstableApi
class EscolhaActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val precisaCarregar = Remote.channels.isEmpty() && Enderecos.canais != null
        if (precisaCarregar) setContentView(R.layout.estado_carregando)
        lifecycleScope.launch(semDerrubar) {
            if (precisaCarregar) withContext(Dispatchers.IO) {
                Remote.loadCached(this@EscolhaActivity)
                if (MinhasListas.canaisDaTela().isEmpty()) Remote.refresh(applicationContext)
            }
            if (isFinishing) return@launch
            when (intent.action) {
                Intent.ACTION_VIEW -> if (Links.abrir(this@EscolhaActivity, intent.data)) {
                    finish()
                    return@launch
                }
                Intent.ACTION_SEARCH -> {
                    val termo = intent.getStringExtra(SearchManager.QUERY).orEmpty()
                    val canais = buscarCanais(termo)
                    if (canais.isNotEmpty()) {
                        findViewById<View>(R.id.carregandoConteudo)?.visibility = View.GONE
                        Painel.mostrar(this@EscolhaActivity, getString(R.string.menu_buscar),
                            canais.map { canal -> Painel.Item(canal.name) { abrirCanal(canal.name) } },
                            aoFechar = { finish() })
                        return@launch
                    }
                    Toast.makeText(this@EscolhaActivity, getString(R.string.busca_sem_canal, termo), Toast.LENGTH_SHORT).show()
                }
            }
            abrirCanal()
        }
    }

    private fun abrirCanal(nome: String? = null) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, nome))
        finish()
    }
}

internal fun buscarCanais(termo: String): List<Channel> {
    val busca = termo.trim()
    if (busca.isEmpty()) return emptyList()
    return MinhasListas.canaisDaTela().filter { it.name.contains(busca, ignoreCase = true) }.take(40)
}
