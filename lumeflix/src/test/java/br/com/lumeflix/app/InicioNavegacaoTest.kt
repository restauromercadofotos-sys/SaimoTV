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
        assertEquals(listOf("Novas séries", continuar, "Em alta hoje", "Animes"),
            organizar("Em alta hoje", "Novas séries", continuar, "Em alta", "Séries em alta",
                "Séries novas de 2026", "Animes", "ANIMES", continuar))
    }

    @Test fun `mantem categorias disponiveis sem ranking e sem historico`() {
        assertEquals(listOf("Em alta", "Séries em alta", "Doramas"), organizar("Em alta", "Séries em alta", "Doramas"))
        assertEquals(listOf(continuar), organizar(continuar))
    }

    @Test fun `ranking limita dez posicoes e preserva a ordem da fonte`() {
        val cartoes = (1..13).map { Inicio.Cartao("Filme $it", "", "") {} }
        val fila = Inicio.Fila("Top 10 filmes em alta hoje", cartoes, comFichas = false)
        val ranking = Inicio.organizarCatalogo(listOf(fila), continuar).single().cartoes
        assertEquals((1..10).toList(), ranking.map { it.ranking })
        assertEquals(cartoes.take(10).map { it.titulo }, ranking.map { it.titulo })
    }

    @Test fun `seis filmes recebem mais quatro distintos sem misturar series`() {
        fun cartao(id: Int, serie: Boolean = false) = Inicio.Cartao("Título $id", "", "",
            alvo = Alvo("Título $id", serie, "", tmdbId = id)) {}
        val diaria = Inicio.Fila("Top 10 filmes em alta hoje", (1..6).map { cartao(it) }, comFichas = false)
        val local = Inicio.Fila("Em alta", listOf(cartao(1), cartao(99, true)) + (7..11).map { cartao(it) }, comFichas = false)
        val ranking = Inicio.organizarCatalogo(listOf(diaria, local), continuar).single().cartoes
        assertEquals((1..10).toList(), ranking.map { it.alvo?.tmdbId })
        assertEquals((1..10).toList(), ranking.map { it.ranking })
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
