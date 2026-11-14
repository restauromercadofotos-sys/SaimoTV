package br.com.saimo.tv

import org.junit.Assert.*
import org.junit.Test

class CanalNaTvTest {
    @Test fun `fileiras separam tipos e removem repeticoes sem perder ordem`() {
        val filme = Destaques.Item("Filme", 'f', "F", "2026", "")
        val serie = Destaques.Item("Série", 's', "S", "2026", "")
        val anime = Destaques.Item("Anime", 'a', "A", "2026", "")
        val filas = listOf(Destaques.Fila("Mistos", listOf(filme, serie)),
            Destaques.Fila("Mais", listOf(serie, anime)))
        assertEquals(listOf(serie, anime), CanalNaTv.selecionar(filas, true))
        assertEquals(listOf(filme), CanalNaTv.selecionar(filas, false))
        assertTrue(CanalNaTv.selecionar(emptyList(), true).isEmpty())
    }
    @Test fun `limita cada canal a vinte cartoes`() {
        val itens = (1..30).map { Destaques.Item("Série $it", 's', "S", "", "") }
        assertEquals(itens.take(20), CanalNaTv.selecionar(listOf(Destaques.Fila("Séries", itens)), true))
    }
}
