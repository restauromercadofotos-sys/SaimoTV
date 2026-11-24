package br.com.lumeflix.app

import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

internal object AddonMenu {
    fun abrir(ctx: AppCompatActivity) {
        val addons = Addons.instalados()
        AlertDialog.Builder(ctx).setTitle("Addons do Stremio")
            .setItems((listOf("Importar addon") + addons.map { it.nome }).toTypedArray()) { _, i ->
                if (i == 0) {
                    val campo = EditText(ctx).apply {
                        hint = "Link do manifest.json"
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        isSingleLine = true
                    }
                    AlertDialog.Builder(ctx).setTitle("Importar addon").setView(campo)
                        .setPositiveButton("Importar") { _,_ -> consultar(ctx,campo.text.toString(),true) }
                        .setNegativeButton("Cancelar",null).show()
                } else {
                    val a = addons[i-1]
                    AlertDialog.Builder(ctx).setTitle(a.nome)
                        .setItems(arrayOf("Testar manifesto","Atualizar","Remover")) { _, j ->
                            if (j == 2) Addons.remover(a) else consultar(ctx,a.link,j == 1)
                        }.show()
                }
            }.show()
    }

    private fun consultar(ctx: AppCompatActivity, link: String, salvar: Boolean) {
        ctx.lifecycleScope.launch {
            val a = runCatching { Addons.importar(link,salvar) }.getOrNull()
            if (a == null) Toast.makeText(ctx,"Verifique o link e se o addon oferece filmes ou séries.",Toast.LENGTH_LONG).show()
            else AlertDialog.Builder(ctx).setTitle(a.nome).setMessage("Manifesto válido. Recursos: " +
                a.recursos.joinToString { it.getString("name") } +
                ". A disponibilidade de vídeo é verificada por título.").setPositiveButton("OK",null).show()
        }
    }

    fun proxima(ctx: AppCompatActivity, cat: Addons.Catalogo, busca: String?, skip: Int) {
        ctx.lifecycleScope.launch {
            val page = cat.pagina(busca,skip)
            if (page.isEmpty()) Toast.makeText(ctx,"Fim do catálogo",Toast.LENGTH_SHORT).show()
            else AlertDialog.Builder(ctx).setTitle(cat.nome)
                .setItems(page.map { it.getString("name") }.toTypedArray()) { _, i -> Addons.cartao(ctx,cat,page[i]).aoEscolher() }
                .setPositiveButton("Próxima página") { _,_ -> proxima(ctx,cat,busca,skip+page.size) }.show()
        }
    }
}
