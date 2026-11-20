package br.com.sintonia.tv

import android.content.Context
import android.content.SharedPreferences

/**
 * O que a pessoa escolheu na tela de Ajustes.
 *
 * Tudo aqui tem um padrão que serve à maioria — quem nunca abrir os Ajustes
 * não perde nada. Os valores ficam num arquivo só para a tela de Ajustes e o
 * resto do app lerem a mesma coisa sem combinar nomes em dois lugares.
 */
object Preferencias {

    private const val ARQUIVO = "ajustes"

    private lateinit var prefs: SharedPreferences

    fun iniciar(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
    }

    /** Tamanho da legenda: 0 pequena, 1 média, 2 grande, 3 enorme. */
    var legenda: Int
        get() = prefs.getInt("legenda", 1)
        set(valor) = prefs.edit().putInt("legenda", valor).apply()

    /** Fundo escuro atrás da legenda, para ler em cima de cena clara. */
    var legendaFundo: Boolean
        get() = prefs.getBoolean("legendaFundo", true)
        set(valor) = prefs.edit().putBoolean("legendaFundo", valor).apply()

    /** Idioma da legenda escolhido da última vez ("" = sem legenda). */
    var legendaIdioma: String
        get() = prefs.getString("legendaIdioma", "") ?: ""
        set(valor) = prefs.edit().putString("legendaIdioma", valor).apply()

    /** Qualidade limitada para economizar internet. */
    var economia: Boolean
        get() = prefs.getBoolean("economia", false)
        set(valor) = prefs.edit().putBoolean("economia", valor).apply()

    /** Áudio Inteligente: normaliza dinamicamente o volume entre diferentes fontes e canais. */
    var audioInteligente: Boolean
        get() = prefs.getBoolean("audioInteligente", true)
        set(valor) = prefs.edit().putBoolean("audioInteligente", valor).apply()

    /** Zero Delay: "off" (padrão), "equilibrado" (baixo atraso inteligente) ou "agressivo" (ultra tempo real). */
    var zeroDelay: String
        get() = prefs.getString("zeroDelay", "off") ?: "off"
        set(valor) = prefs.edit().putString("zeroDelay", valor).apply()

    /** Super Resolução (Upscaling): "off" (padrão), "hd" (HD -> Full HD) ou "4k" (Full HD -> 4K). */
    var upscaling: String
        get() = prefs.getString("upscaling", "off") ?: "off"
        set(valor) = prefs.edit().putString("upscaling", valor).apply()

    /** Onde o app abre: "inicio" ou "canal" (o último canal assistido). */
    var abrirEm: String
        get() = prefs.getString("abrirEm", "inicio") ?: "inicio"
        set(valor) = prefs.edit().putString("abrirEm", valor).apply()

    /** Nome do último canal assistido, para voltar nele. */
    var ultimoCanal: String?
        get() = prefs.getString("ultimoCanal", null)
        set(valor) = prefs.edit().putString("ultimoCanal", valor).apply()

    /** Canal assistido antes do atual, para a tecla de canal anterior. */
    var canalAnterior: String?
        get() = prefs.getString("canalAnterior", null)
        set(valor) = prefs.edit().putString("canalAnterior", valor).apply()

    /** Buscas feitas, da mais recente para a mais antiga. */
    var historicoBusca: List<String>
        get() = prefs.getString("historico", "").orEmpty().split("\n").filter { it.isNotBlank() }
        set(valor) = prefs.edit().putString("historico", valor.take(12).joinToString("\n")).apply()

    fun lembrarBusca(termo: String) {
        val limpo = termo.trim()
        if (limpo.length < 2) return
        historicoBusca = listOf(limpo) + historicoBusca.filterNot { it.equals(limpo, ignoreCase = true) }
    }

    /** ID curto do aparelho, para suporte: o mesmo que a telemetria usa. */
    fun idCurto(context: Context): String =
        context.getSharedPreferences("telemetria", Context.MODE_PRIVATE)
            .getString("id", null)?.take(8)?.uppercase() ?: "—"
}
