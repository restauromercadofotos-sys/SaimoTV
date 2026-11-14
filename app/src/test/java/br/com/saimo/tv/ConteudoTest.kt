package br.com.saimo.tv

import org.junit.Assert.*
import org.junit.Test

class ConteudoTest {
    @Test fun `cada variante permite apenas seu conteudo`() {
        val tv = BuildConfig.TV_AO_VIVO
        assertEquals(tv, Conteudo.permiteAba(Aba.AO_VIVO))
        assertEquals(tv, Conteudo.permiteAba(Aba.EVENTOS))
        for (aba in listOf(Aba.ON_DEMAND, Aba.FAVORITOS, Aba.EXTRAS, Aba.BUSCAR)) {
            assertEquals(!tv, Conteudo.permiteAba(aba))
        }
        assertFalse(Conteudo.permiteAba(Aba.RADIOS))
        assertTrue(Conteudo.permiteAba(Aba.INICIO))
        assertTrue(Conteudo.permiteAba(Aba.AJUSTES))
        assertEquals(tv, Conteudo.permiteLink("canal"))
        assertEquals(!tv, Conteudo.permiteLink("titulo"))
        assertEquals(!tv, Conteudo.permiteLink("assistir"))
        assertFalse(Conteudo.permiteLink(null))
        assertFalse(Conteudo.permiteLink("desconhecido"))
        for (comando in listOf("tune", "multi", "activeScreen", "tvAudio")) {
            assertEquals(tv, Conteudo.permiteComando(comando))
        }
        assertEquals(!tv, Conteudo.permiteComando("playVod"))
        assertEquals(!tv, Conteudo.permiteComando("playMedia"))
        assertTrue(Conteudo.permiteComando("key"))
    }
}
