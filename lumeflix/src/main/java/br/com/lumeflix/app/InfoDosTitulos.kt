package br.com.lumeflix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ano, nota, classificação indicativa e popularidade de cada título, para os
 * filtros do On Demand. Vem de `detalhes.txt` (gerar_detalhes.py, do TMDB).
 *
 * Sem o arquivo os filtros de ano, idade e nota não aparecem, e a ordem cai no
 * ano do nome do título.
 */
object InfoDosTitulos {

    /** Classificação: 0 = Livre, 10..18 = idade; -1 = não informada. */
    class Info(val ano: Int, val nota: Float, val idade: Int, val popularidade: Int)

    private var filmes: HashMap<String, Info> = HashMap()
    private var series: HashMap<String, Info> = HashMap()

    val prontos: Boolean get() = filmes.isNotEmpty() || series.isNotEmpty()

    fun de(titulo: String, serie: Boolean): Info? {
        val mapa = if (serie) series else filmes
        return mapa[titulo]
    }

    private val carregarMutex = Mutex()

    suspend fun carregar(context: Context) = withContext(Dispatchers.IO) {
        carregarMutex.withLock {
            if (prontos) return@withLock
            val texto = Vod.texto(context, "detalhes.txt") ?: return@withLock
            val f = HashMap<String, Info>(40_000)
            val s = HashMap<String, Info>(12_000)
            for (linha in texto.lineSequence()) {
                if (linha.isEmpty() || linha[0] == '#') continue
                val c = linha.split('\t')
                if (c.size < 6) continue
                val idade = when (val k = c[4]) { "L" -> 0; "" -> -1; else -> k.toIntOrNull() ?: -1 }
                val info = Info(c[2].toIntOrNull() ?: 0, c[3].toFloatOrNull() ?: 0f, idade, c[5].toIntOrNull() ?: 0)
                if (c[0] == "s") s[c[1]] = info else f[c[1]] = info
            }
            filmes = f
            series = s
        }
    }
}
