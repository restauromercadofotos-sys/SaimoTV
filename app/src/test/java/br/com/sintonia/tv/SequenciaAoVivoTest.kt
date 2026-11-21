package br.com.sintonia.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenciaAoVivoTest {
    @Test fun detectaSomenteQuandoOCicloCompleta() {
        val sequencia = SequenciaAoVivo()
        for (segmento in listOf("a", "b", "c", "a", "b")) {
            assertFalse(sequencia.registrar(segmento))
        }
        assertTrue(sequencia.registrar("c"))
    }

    @Test fun urlsReutilizadasComConteudoNovoNaoFormamCiclo() {
        val sequencia = SequenciaAoVivo()
        for (segmento in listOf("a:1", "b:1", "c:1", "a:2", "b:2", "c:2")) {
            assertFalse(sequencia.registrar(segmento))
        }
    }

    @Test fun detectaCicloDeDoisSegmentosDepoisDeTresPassagens() {
        val sequencia = SequenciaAoVivo()
        listOf("a", "b", "a", "b", "a").forEach { assertFalse(sequencia.registrar(it)) }
        assertTrue(sequencia.registrar("b"))
    }

    @Test fun ignoraEnderecosJaTentados() {
        val fontes = listOf("https://a", "https://a", "https://b", "https://a").map { Source(it) }
        assertEquals(2, proximaFonteDistinta(0, fontes))
        assertEquals(-1, proximaFonteDistinta(2, fontes))
    }

    @Test fun descartaHistoricoAoTrocarAFonte() {
        val sequencia = SequenciaAoVivo()
        listOf("a", "b", "c").forEach { sequencia.registrar(it) }
        sequencia.limpar()
        for (segmento in listOf("a", "b", "c")) {
            assertFalse(sequencia.registrar(segmento))
        }
    }
}
