package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class FonteAoVivoTest {
    @Test fun `fim avanca quando ha outra fonte`() {
        assertEquals(1, proximaFonteOuAtual(atual = 0, total = 3))
    }

    @Test fun `fim repete quando a fonte e unica`() {
        assertEquals(0, proximaFonteOuAtual(atual = 0, total = 1))
    }
}
