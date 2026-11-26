package br.com.lumeflix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuscaFiltroTest {
    @Test fun addonsNaoSaoConsultadosAteTresCaracteres() {
        assertFalse(buscaRemotaDeAddons(""))
        assertFalse(buscaRemotaDeAddons("a"))
        assertFalse(buscaRemotaDeAddons("it"))
        assertTrue(buscaRemotaDeAddons("fil"))
    }
}
