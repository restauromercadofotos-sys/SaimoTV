package br.com.lumeflix.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.ContentProviderOperation
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    internal fun selecionar(filas: List<Destaques.Fila>, series: Boolean): List<Destaques.Item> {
        val categoria = if (series) "series em alta" else "em alta"
        return filas.sortedByDescending { Vod.normalizar(it.titulo) == categoria }
            .asSequence().flatMap { it.itens.asSequence() }
            .filter { it.serie == series && it.capa.isNotBlank() }
            .distinctBy { Triple(it.tipo, it.titulo, it.ano) }.take(20).toList()
    }

    private val publicacao = Mutex()

    suspend fun publicar(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 26) return@withContext true
        publicacao.withLock {
            try {
                atualizar(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LumeFlix-Launcher", "Falha ao publicar canais de prévia", e)
                false
            }
        }
    }

    private suspend fun atualizar(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ajudante = PreviewChannelHelper(context)
        val existentes = ajudante.allChannels.filter { it.packageName == context.packageName }
        val canais = listOf(true to "Séries em alta", false to "Filmes em alta").map { (series, titulo) ->
            val chave = if (series) "canal" else "filmes"
            val salvo = prefs.getLong(chave, -1L)
            val antigo = existentes.firstOrNull { it.internalProviderId == chave || it.id == salvo }
            val id = antigo?.id ?: run {
                val logo = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                context.getDrawable(R.drawable.lumeflix_app_icon)!!.apply {
                    setBounds(0, 0, logo.width, logo.height)
                    draw(Canvas(logo))
                }
                val canal = PreviewChannel.Builder().setDisplayName(titulo)
                    .setInternalProviderId(chave)
                    .setAppLinkIntentUri(Uri.parse("${BuildConfig.LINK_SCHEME}://inicio"))
                    .setLogo(logo).build()
                if (series) ajudante.publishDefaultChannel(canal) else ajudante.publishChannel(canal)
            }
            check(id >= 0) { "Canal de prévia recusado pelo provedor" }
            if (antigo == null) prefs.edit().putLong(chave, id).remove("paisagem_$chave").apply()
            else if (antigo.displayName.toString() != titulo || antigo.internalProviderId != chave) {
                context.contentResolver.update(TvContractCompat.buildChannelUri(id), android.content.ContentValues().apply {
                    put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, titulo)
                    put(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID, chave)
                }, null, null)
            }
            Triple(series, chave, id)
        }
        val pendentes = canais.filter { (_, chave, _) ->
            System.currentTimeMillis() - prefs.getLong("paisagem_$chave", 0L) >= VALIDADE_MS
        }
        if (pendentes.isEmpty()) return true
        val filas = Destaques.filas(context)
        var completo = true
        for ((series, chave, canal) in pendentes) {
            val itens = selecionar(filas, series)
            if (itens.isEmpty()) {
                completo = false
                continue
            }
            val operacoes = arrayListOf(ContentProviderOperation.newDelete(
                TvContractCompat.buildPreviewProgramsUriForChannel(canal)).build())
            itens.forEachIndexed { i, item ->
                val ficha = Detalhes.de(item.titulo, item.serie)
                val arte = ficha?.arteLauncher ?: ficha?.fundo ?: return@forEachIndexed
                val alvo = Alvo(item.titulo, item.serie, item.letra, item.ano,
                    if (item.daColecao) item.colecao else "")
                val programa = PreviewProgram.Builder().setChannelId(canal)
                    .setType(if (series) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                    .setTitle(Generos.semAno(item.titulo)).setWeight(1000 - i)
                    .setIntentUri(Links.ficha(alvo)).setInternalProviderId(item.titulo)
                    .apply {
                        setPosterArtUri(Uri.parse(arte))
                        setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                        item.ano.toIntOrNull()?.let { setReleaseDate(it.toString()) }
                    }.build()
                operacoes += ContentProviderOperation.newInsert(TvContractCompat.PreviewPrograms.CONTENT_URI)
                    .withValues(programa.toContentValues()).build()
            }
            if (operacoes.size == 1) {
                completo = false
                continue
            }
            context.contentResolver.applyBatch(TvContractCompat.AUTHORITY, operacoes)
            prefs.edit().putLong("paisagem_$chave", System.currentTimeMillis()).apply()
        }
        return completo
    }
}
