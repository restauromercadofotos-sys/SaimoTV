package br.com.sintonia.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi

@UnstableApi
class SemListaActivity : TelaComEstadoVazio() {

    companion object {
        fun abrir(context: Context) = context.startActivity(Intent(context, SemListaActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sem_lista)
        mostrarVazio(getString(
            if ((Enderecos.canais != null || MinhasListas.todas().isNotEmpty())) R.string.vazio_falhou else R.string.vazio_canais))
    }

    override fun aoMudarListas() {
        if (MinhasListas.canaisDaTela().isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } else {
            mostrarVazio(getString(R.string.vazio_falhou))
        }
    }
}
