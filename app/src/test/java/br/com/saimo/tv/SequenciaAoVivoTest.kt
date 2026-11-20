package br.com.saimo.tv

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

    @Test fun descartaHistoricoAoTrocarAFonte() {
        val sequencia = SequenciaAoVivo()
        listOf("a", "b", "c").forEach { sequencia.registrar(it) }
        sequencia.limpar()
        for (segmento in listOf("a", "b", "c")) {
            assertFalse(sequencia.registrar(segmento))
        }
    }
}
