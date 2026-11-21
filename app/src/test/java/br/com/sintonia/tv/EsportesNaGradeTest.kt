package br.com.sintonia.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EsportesNaGradeTest {
    private val canal = Channel("Canal de teste", sources = emptyList())
    private fun programa(titulo: String, categoria: String = "") = Programme(titulo, categoria, 10, 20)

    @Test fun identificaEsporteNaGradeDeQualquerCanal() {
        assertEquals("Vôlei", modalidadeDaGrade(programa("Superliga de Vôlei"), canal))
        assertEquals("Fórmula 1", modalidadeDaGrade(programa("GP do Brasil", "Fórmula 1"), canal))
        assertEquals("Basquete", modalidadeDaGrade(programa("NBA: Boston x Miami"), canal))
        assertEquals("Futebol", modalidadeDaGrade(programa("Brasileirão"), canal))
        assertNull(modalidadeDaGrade(programa("Jornal da Noite"), canal))
    }
}
