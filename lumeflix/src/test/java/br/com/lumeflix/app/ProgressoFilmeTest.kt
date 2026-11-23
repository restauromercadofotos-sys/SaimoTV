package br.com.lumeflix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressoFilmeTest {
    @Test fun mesmoFilmeComGrafiasDiferentesPreservaDistincaoEntreRemakes() {
        val antigo = Progresso.Endereco("A Odisseia (2026)", false, "A", "2026", tmdbId = 42)
        val item = Progresso.Andamento("f|A Odisseia (2026)", antigo.titulo, antigo.titulo,
            false, 0.3f, 1, antigo)
        assertTrue(Progresso.mesmoFilme(item, antigo.copy(titulo = "The Odyssey"), "The Odyssey"))
        assertTrue(Progresso.mesmoFilme(item, antigo.copy(titulo = "A Odisseia", tmdbId = 0), "A Odisseia"))
        assertFalse(Progresso.mesmoFilme(item, antigo.copy(ano = "1997", tmdbId = 17), "A Odisseia"))
    }
}
