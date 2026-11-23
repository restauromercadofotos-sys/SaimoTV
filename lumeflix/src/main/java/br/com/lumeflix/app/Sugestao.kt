package br.com.lumeflix.app

import android.content.Context

internal suspend fun sugerirPorHistorico(contexto: Context, serie: Boolean? = null): Inicio.Fila? {
    val candidatos = Progresso.emAndamento(contexto).filter { serie == null || it.serie == serie }
    val base = candidatos.shuffled().firstOrNull { (it.endereco?.tmdbId ?: 0) > 0 || Generos.id(it.nome, it.serie) != null } ?: return null
    val id = base.endereco?.tmdbId?.takeIf { it > 0 } ?: Generos.id(base.nome, base.serie) ?: return null
    val cartoes = Detalhes.parecidos(contexto, id, base.serie).map { resultado ->
        val a = resultado.achado
        val alvo = Alvo(a.titulo, a.serie, a.letra, a.ano)
        Inicio.Cartao(a.nomeCompleto, resultado.capa.orEmpty(), a.titulo.take(1), alvo = alvo) {
            FichaActivity.abrir(contexto, alvo)
        }
    }
    return if (cartoes.isEmpty()) null else Inicio.Fila("Porque você viu ${Generos.semAno(base.titulo)}", cartoes)
}
