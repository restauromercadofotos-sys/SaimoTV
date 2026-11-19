package br.com.lumeflix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class InicioNavegacaoTest {
    private val continuar = "Continue assistindo"

    private fun organizar(vararg nomes: String): List<String> {
        val cartao = Inicio.Cartao("Exemplo", "", "E") {}
        val filas = nomes.map { Inicio.Fila(it, listOf(cartao), comFichas = false) }
        return Inicio.organizarCatalogo(filas, continuar).map { it.titulo }
    }

    @Test fun `continuar fica em segundo no cache e apos os destaques chegarem`() {
        assertEquals(listOf("Em alta", continuar, "Animes"), organizar(continuar, "Em alta", "Animes"))
        assertEquals(listOf("Em alta hoje", continuar, "Novas séries", "Animes"),
            organizar("Em alta hoje", "Novas séries", continuar, "Em alta", "Séries em alta",
                "Séries novas de 2026", "Animes", "ANIMES", continuar))
    }

    @Test fun `mantem categorias disponiveis sem ranking e sem historico`() {
        assertEquals(listOf("Em alta", "Séries em alta", "Doramas"), organizar("Em alta", "Séries em alta", "Doramas"))
        assertEquals(listOf(continuar), organizar(continuar))
    }

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
