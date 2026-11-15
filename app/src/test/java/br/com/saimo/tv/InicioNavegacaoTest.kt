package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class InicioNavegacaoTest {
    @Test fun `mantem a coluna ao subir ou descer`() {
        assertEquals(3, Inicio.indiceNaFilaVertical(indiceAtual = 3, totalDestino = 8))
    }

    @Test fun `vai ao primeiro quando a outra fila e menor`() {
        assertEquals(0, Inicio.indiceNaFilaVertical(indiceAtual = 7, totalDestino = 3))
    }

    @Test fun `fila vazia nao recebe foco`() {
        assertEquals(-1, Inicio.indiceNaFilaVertical(indiceAtual = 2, totalDestino = 0))
    }
}
