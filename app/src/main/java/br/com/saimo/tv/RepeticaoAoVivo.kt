package br.com.saimo.tv

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

/** Detecta um ciclo completo de segmentos em posições novas da transmissão. */
internal class SequenciaAoVivo {
    private val recentes = ArrayDeque<String>()

    fun limpar() = recentes.clear()

    fun registrar(segmento: String): Boolean {
        recentes.addLast(segmento)
        if (recentes.size > 128) recentes.removeFirst()
        for (periodo in 3..(recentes.size / 2)) {
            val inicio = recentes.size - periodo * 2
            val meio = inicio + periodo
            val ciclo = recentes.subList(inicio, meio)
            if (ciclo == recentes.subList(meio, recentes.size) && ciclo.toSet().size >= 3) return true
        }
        return false
    }
}

@OptIn(UnstableApi::class)
internal class RepeticaoAoVivo(private val aoRepetir: () -> Unit) : AnalyticsListener {
    private val sequencia = SequenciaAoVivo()
    private var desde = Long.MAX_VALUE
    private var ultimaPosicao = C.TIME_UNSET
    private var detectou = false

    fun reiniciar(ativa: Boolean) {
        desde = if (ativa) SystemClock.elapsedRealtime() else Long.MAX_VALUE
        ultimaPosicao = C.TIME_UNSET
        detectou = false
        sequencia.limpar()
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        if (detectou || loadEventInfo.elapsedRealtimeMs - loadEventInfo.loadDurationMs < desde ||
            mediaLoadData.dataType != C.DATA_TYPE_MEDIA || mediaLoadData.trackType == C.TRACK_TYPE_AUDIO ||
            mediaLoadData.trackType == C.TRACK_TYPE_TEXT) return
        val posicao = mediaLoadData.mediaStartTimeMs
        if (posicao == C.TIME_UNSET || (ultimaPosicao != C.TIME_UNSET && posicao <= ultimaPosicao)) return
        ultimaPosicao = posicao
        // Uma CDN pode reutilizar a URL para segmentos novos em um buffer circular.
        val etag = loadEventInfo.responseHeaders.entries
            .firstOrNull { it.key.equals("ETag", ignoreCase = true) }?.value?.firstOrNull()
        if (etag.isNullOrBlank() || etag.startsWith("W/", ignoreCase = true)) {
            sequencia.limpar()
            return
        }
        val pedido = loadEventInfo.dataSpec
        if (sequencia.registrar("${pedido.uri}|${pedido.position}|${pedido.length}|$etag")) {
            detectou = true
            aoRepetir()
        }
    }
}
