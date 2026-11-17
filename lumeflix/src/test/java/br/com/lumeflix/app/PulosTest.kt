package br.com.lumeflix.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PulosTest {
    @Test fun aberturaERecapUsamMilissegundos() {
        val json = JSONObject("""{
            "tvdb_id": 169,
            "intro": [{"start_ms": 12000, "end_ms": 90000}],
            "recap": [{"start_ms": null, "end_ms": 11000}],
            "credits": [{"start_ms": 2600000, "end_ms": null}]
        }""")
        val marcas = Pulos.Marcas(Pulos.ler(json))
        assertEquals(3, marcas.trechos.size)
        assertEquals(Pulos.Tipo.RECAPITULACAO, marcas.em(5000, 2700000)?.tipo)
        assertEquals(Pulos.Tipo.ABERTURA, marcas.em(20000, 2700000)?.tipo)
        assertNull(marcas.em(92000, 2700000))
        assertEquals(2600000L, marcas.creditos)
    }

    @Test fun valoresInvalidosNaoPodemGerarSeekIncorreto() {
        val json = JSONObject("""{
            "intro": [
                {"start_ms": 200000, "end_ms": 100000},
                {"start_ms": -1000, "end_ms": 20000},
                {"start_ms": 30000, "end_ms": null},
                {"start_ms": 10000, "end_ms": 70000}
            ]
        }""")
        val trechos = Pulos.ler(json)
        assertEquals(1, trechos.size)
        assertEquals(10000L, trechos[0].inicio)
        assertEquals(70000L, trechos[0].fim)
    }

    @Test fun respostaSemMarcasNaoExibeBotaoDePular() {
        val marcas = Pulos.Marcas(Pulos.ler(JSONObject("""{"tvdb_id":169}""")))
        assertFalse(marcas.trechos.isNotEmpty())
        assertNull(marcas.em(10000, 120000))
    }
}
