package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class FonteAoVivoTest {
    @Test fun `fim avanca quando ha outra fonte`() {
        assertEquals(1, proximaFonteOuAtual(atual = 0, total = 3))
    }

    @Test fun `fim encerra quando a fonte e unica`() {
        assertEquals(-1, proximaFonteOuAtual(atual = 0, total = 1))
    }

    @Test fun `ultima fonte nao repete trecho encerrado`() {
        assertEquals(-1, proximaFonteOuAtual(atual = 2, total = 3))
    }

    @Test fun `vinte segundos nao renovam tentativas de recuperacao`() {
        assertEquals(false, reproducaoEstavel(1_000, 21_000))
        assertEquals(true, reproducaoEstavel(1_000, 61_000))
        assertEquals(false, reproducaoEstavel(0, 100_000))
    }
}
