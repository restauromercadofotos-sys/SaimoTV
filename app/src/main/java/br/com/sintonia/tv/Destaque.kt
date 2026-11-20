package br.com.sintonia.tv

import android.app.Activity
import android.widget.ImageView
import android.widget.TextView

internal class Destaque(
    private val tela: Activity,
    fundo: ImageView,
    private val titulo: TextView,
    private val meta: TextView,
    private val sinopse: TextView,
) {
    init { fundo.setImageResource(R.drawable.fundo_app) }

    fun padrao(tituloPadrao: String, metaPadrao: String) {
        titulo.text = tituloPadrao
        meta.text = metaPadrao
        sinopse.text = ""
    }

    fun mostrar(cartao: Inicio.Cartao) {
        titulo.text = cartao.titulo
        meta.text = cartao.meta ?: cartao.subtitulo?.let { tela.getString(R.string.inicio_canal_agora, it) }.orEmpty()
        sinopse.text = cartao.sinopse.orEmpty()
    }
}
