package br.com.sintonia.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object Enderecos {

    private const val PREFS = "listas"
    private const val CANAIS = "linkCanais"
    /// Quando os endereços foram escolhidos (ms; 0 = nunca). Na sincronia
    /// com o celular pelo Sintonia Connect vale o mais recente.
    private const val EM = "linksEm"

    /// Base já normalizada (termina em `/`), ou nulo quando não há endereço.
    @Volatile
    var canais: String? = null
        private set

    /** Chamado uma vez, na criação do app, antes de qualquer tela. */
    fun iniciar(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Valor guardado vale (vazio = desativada); sem valor, não há endereço.
        canais = normalizar(prefs.getString(CANAIS, "").orEmpty())
    }

    /**
     * Guarda os endereços (já normalizados; nulo apaga). Quem mudou tem os
     * dados antigos apagados do cache.
     */
    fun escolhidosEm(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(EM, 0L)

    fun definir(context: Context, novoCanais: String?,
                em: Long = System.currentTimeMillis()) {
        val mudouCanais = novoCanais != canais
        canais = novoCanais
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(CANAIS, novoCanais.orEmpty())
            putLong(EM, em)
        }.apply()
        if (mudouCanais) apagarDadosDeCanais(context)
        Connect.avisarMudancaLocal(context, 1000)
    }

    /** Catálogo, restritos, rádios, guia e o que foi montado em cima deles. */
    fun apagarDadosDeCanais(context: Context) {
        for (nome in listOf("catalogo.txt", "canais.txt", "restritos.txt", "radios.txt", "guia.txt")) {
            File(context.filesDir, nome).delete()
            File(context.filesDir, "$nome.etag").delete()
        }
        File(context.cacheDir, "epg.json").delete()
        File(context.cacheDir, "epg.sig").delete()
        Remote.limpar()
        GuiaTabelas.limpar()
        Epg.liberar()
    }

    // MARK: - Normalização

    /**
     * Endereço digitado -> endereço-base com `/` no fim, ou nulo se vazio ou
     * inválido. O formato aceito é:
     * trim; sem `://` vira `https://`; só http/https; sem espaço no meio e com
     * host; tira `?query` e `#fragmento`; descarta o último trecho do caminho
     * se tiver ponto (a pessoa colou o arquivo, não a pasta); `/` no fim.
     */
    fun normalizar(texto: String): String? {
        val limpo = texto.trim()
        if (limpo.isEmpty() || limpo.any { it.isWhitespace() }) return null
        val completo = if ("://" in limpo) limpo else "https://$limpo"
        val esquema = completo.substringBefore("://").lowercase()
        if (esquema != "http" && esquema != "https") return null
        var resto = completo.substringAfter("://")
        resto = resto.substringBefore('#').substringBefore('?')
        val host = resto.substringBefore('/')
        if (host.isEmpty() || host.startsWith(":") || host.contains("@")) return null
        var caminho = if ('/' in resto) resto.substring(resto.indexOf('/')) else ""
        val ultimo = caminho.substringAfterLast('/')
        if ('.' in ultimo) caminho = caminho.removeSuffix(ultimo)
        if (!caminho.endsWith("/")) caminho += "/"
        return "$esquema://$host$caminho"
    }

    /** O endereço responde 200 para o arquivo de prova (catalogo.txt / indice.txt)? */
    suspend fun testar(base: String, arquivo: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cliente = Playback.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
            val pedido = Request.Builder().url(base + arquivo)
                .header("User-Agent", Playback.DEFAULT_USER_AGENT)
                .header("Cache-Control", "no-cache").build()
            cliente.newCall(pedido).execute().use { it.code == 200 }
        }.getOrDefault(false)
    }
}
