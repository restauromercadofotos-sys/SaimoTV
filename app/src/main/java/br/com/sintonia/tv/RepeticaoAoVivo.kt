package br.com.sintonia.tv

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import java.security.MessageDigest
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

/** Detecta um ciclo completo de segmentos em posições novas da transmissão. */
internal class SequenciaAoVivo {
    private val recentes = ArrayDeque<String>()

    fun limpar() = recentes.clear()

    fun registrar(segmento: String): Boolean {
        recentes.addLast(segmento)
        if (recentes.size > 128) recentes.removeFirst()
        if (recentes.size >= 6) {
            val inicio = recentes.size - 6
            val a = recentes[inicio]
            val b = recentes[inicio + 1]
            if (a != b && recentes.subList(inicio, recentes.size) == listOf(a, b, a, b, a, b)) return true
        }
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
    private var digitais: Segmentos? = null

    fun reiniciar(ativa: Boolean) {
        desde = if (ativa) SystemClock.elapsedRealtime() else Long.MAX_VALUE
        ultimaPosicao = C.TIME_UNSET
        detectou = false
        digitais = if (ativa) Segmentos() else null
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
        val digital = digitais?.retirar(loadEventInfo.dataSpec) ?: run {
            sequencia.limpar()
            return
        }
        if (sequencia.registrar(digital)) {
            detectou = true
            aoRepetir()
        }
    }

    fun fabrica(upstream: DataSource.Factory): HlsDataSourceFactory {
        val registro = digitais
        return HlsDataSourceFactory { tipo ->
            val fonte = upstream.createDataSource()
            if (registro == null || tipo != C.DATA_TYPE_MEDIA) fonte else fonteComDigital(fonte, registro)
        }
    }

    private fun fonteComDigital(fonte: DataSource, registro: Segmentos): DataSource =
        object : DataSource by fonte {
            private val hash = MessageDigest.getInstance("SHA-256")
            private var pedido: DataSpec? = null
            private var esperado = C.LENGTH_UNSET.toLong()
            private var lido = 0L
            private var terminou = false

            override fun open(dataSpec: DataSpec): Long {
                hash.reset()
                pedido = dataSpec
                lido = 0
                terminou = false
                esperado = fonte.open(dataSpec)
                return esperado
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = fonte.read(buffer, offset, length)
                if (n == C.RESULT_END_OF_INPUT) terminou = true
                if (n > 0) {
                    hash.update(buffer, offset, n)
                    lido += n
                }
                return n
            }

            override fun close() {
                pedido?.let {
                    if (lido > 0 && (terminou || lido == esperado)) {
                        registro.guardar(it, android.util.Base64.encodeToString(hash.digest(), android.util.Base64.NO_WRAP))
                    }
                }
                pedido = null
                fonte.close()
            }
        }

    private class Segmentos {
        private val digitos = LinkedHashMap<Pair<String, Long>, String>()

        @Synchronized fun guardar(pedido: DataSpec, digital: String) {
            digitos[pedido.uri.toString() to pedido.position] = digital
            if (digitos.size > 64) digitos.remove(digitos.keys.first())
        }

        @Synchronized fun retirar(pedido: DataSpec): String? =
            digitos.remove(pedido.uri.toString() to pedido.position)
    }

}
