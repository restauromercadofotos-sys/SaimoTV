package br.com.lumeflix.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fileiras do app de filmes no launcher Android TV 8+, incluindo Mi Box. */
@UnstableApi
object CanalNaTv {

    private const val PREFS = "canal_na_tv"
    private const val VALIDADE_MS = 12 * 60 * 60 * 1000L

    internal fun selecionar(filas: List<Destaques.Fila>, series: Boolean): List<Destaques.Item> =
        filas.flatMap { it.itens }.filter { it.serie == series }
            .distinctBy { Triple(it.tipo, it.titulo, it.ano) }.take(20)

    suspend fun publicar(context: Context) = withContext(Dispatchers.IO) {
        if (BuildConfig.TV_AO_VIVO || Build.VERSION.SDK_INT < 26) return@withContext
        if (!context.packageManager.hasSystemFeature("android.software.leanback")) return@withContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            val filas = Destaques.filas(context)
            if (filas.isEmpty()) return@withContext
            val ajudante = PreviewChannelHelper(context)
            for ((series, titulo) in listOf(true to "Séries em alta", false to "Filmes em destaque")) {
                val chave = if (series) "canal" else "filmes"
                val itens = selecionar(filas, series)
                if (itens.isEmpty()) continue
                var canal = prefs.getLong(chave, -1L)
                val existe = canal >= 0 && runCatching { ajudante.getPreviewChannel(canal) }.getOrNull() != null
                if (existe && System.currentTimeMillis() - prefs.getLong("quando_$chave", 0L) < VALIDADE_MS) continue
                if (!existe) {
                    val logo = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                    val novo = PreviewChannel.Builder()
                            .setDisplayName(titulo)
                            .setAppLinkIntentUri(Uri.parse("${BuildConfig.LINK_SCHEME}://inicio"))
                            .setLogo(logo)
                            .build()
                    // Só a primeira fileira é padrão; as demais ficam em Personalizar canais.
                    canal = if (series) ajudante.publishDefaultChannel(novo) else ajudante.publishChannel(novo)
                    if (canal < 0) continue
                    prefs.edit().putLong(chave, canal).apply()
                }
                context.contentResolver.update(TvContractCompat.buildChannelUri(canal),
                    android.content.ContentValues().apply { put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, titulo) }, null, null)
                // Refaz os cartões: apaga os velhos e publica os de hoje.
                context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(canal), null, null)
                itens.forEachIndexed { i, item ->
                    val alvo = Alvo(item.titulo, item.serie, item.letra, item.ano,
                        if (item.daColecao) item.colecao else "")
                    ajudante.publishPreviewProgram(
                        PreviewProgram.Builder()
                            .setChannelId(canal)
                            .setType(if (item.serie) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                                     else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                            .setTitle(Generos.semAno(item.titulo))
                            .setWeight(1000 - i)
                            .setIntentUri(Links.ficha(alvo))
                            .setInternalProviderId(item.titulo)
                            .apply {
                                if (item.capa.isNotBlank()) {
                                    setPosterArtUri(Uri.parse(item.capa))
                                    setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
                                }
                                item.ano.toIntOrNull()?.let { setReleaseDate(it.toString()) }
                            }
                            .build())
                }
                prefs.edit().putLong("quando_$chave", System.currentTimeMillis()).apply()
            }
        }.onFailure { Telemetria.erro(it) }
    }
}
