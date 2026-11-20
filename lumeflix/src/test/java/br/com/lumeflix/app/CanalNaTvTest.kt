package br.com.lumeflix.app

import org.junit.Assert.*
import org.junit.Test

class CanalNaTvTest {
    @Test fun `fileiras separam tipos e removem repeticoes sem perder ordem`() {
        val filme = Destaques.Item("Filme", 'f', "F", "2026", "capa")
        val serie = Destaques.Item("Série", 's', "S", "2026", "capa")
        val anime = Destaques.Item("Anime", 'a', "A", "2026", "capa")
        val filas = listOf(Destaques.Fila("Mistos", listOf(filme, serie)),
            Destaques.Fila("Mais", listOf(serie, anime)))
        assertEquals(listOf(serie, anime), CanalNaTv.selecionar(filas, true))
        assertEquals(listOf(filme), CanalNaTv.selecionar(filas, false))
        assertTrue(CanalNaTv.selecionar(emptyList(), true).isEmpty())
    }
    @Test fun `prioriza a fileira em alta e ignora programas sem capa`() {
        val outro = Destaques.Item("Outro", 's', "O", "", "capa")
        val alta = Destaques.Item("Popular", 's', "P", "", "capa")
        val filas = listOf(Destaques.Fila("Recentes", listOf(outro)),
            Destaques.Fila("Séries em alta", listOf(alta.copy(capa = ""), alta)))
        assertEquals(listOf(alta, outro), CanalNaTv.selecionar(filas, true))
    }
    @Test fun `limita cada canal a vinte cartoes`() {
        val itens = (1..30).map { Destaques.Item("Série $it", 's', "S", "", "capa") }
        assertEquals(itens.take(20), CanalNaTv.selecionar(listOf(Destaques.Fila("Séries", itens)), true))
    }
}
