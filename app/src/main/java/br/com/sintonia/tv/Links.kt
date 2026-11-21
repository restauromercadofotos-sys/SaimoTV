package br.com.sintonia.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.util.UnstableApi

@UnstableApi
object Links {
    private const val ESQUEMA = "sintonia-tv"

    fun canal(nome: String): Uri =
        Uri.Builder().scheme(ESQUEMA).authority("canal").appendQueryParameter("n", nome).build()

    fun abrir(context: Context, uri: Uri?): Boolean {
        if (uri?.scheme != ESQUEMA || uri.host != "canal") return false
        val nome = uri.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: return false
        context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, nome))
        return true
    }
}
