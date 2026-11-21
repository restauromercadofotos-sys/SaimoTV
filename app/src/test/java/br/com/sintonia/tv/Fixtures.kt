package br.com.sintonia.tv

/** Arquivos de teste pequenos e inventados (src/test/resources/fixtures). */
object Fixtures {
    fun ler(nome: String): String =
        Fixtures::class.java.classLoader!!.getResourceAsStream("fixtures/$nome")!!
            .bufferedReader().use { it.readText() }
}
