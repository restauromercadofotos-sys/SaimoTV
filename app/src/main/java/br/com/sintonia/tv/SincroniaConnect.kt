package br.com.sintonia.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SincroniaConnect {

    fun pacote(context: Context): JSONObject {

        val favCanais = JSONArray()
        Favorites.load(context)
        for (nome in Favorites.todos()) favCanais.put(nome)
        return JSONObject().apply {
            put("t", "dados")
            put("favCanais", favCanais)
            put("listas", JSONObject().apply {
                put("canais", Enderecos.canais.orEmpty())
                put("em", Enderecos.escolhidosEm(context))
                put("minhas", JSONArray().apply {
                    for (l in MinhasListas.todas()) {
                        val link = l.link ?: continue
                        put(JSONObject().put("nome", l.nome).put("link", link))
                    }
                })
            })
        }
    }

    /** Os endereços e as listas adicionadas que vieram do celular. */
    private suspend fun aplicarListas(context: Context, l: JSONObject) {
        val em = l.optLong("em")
        val local = Enderecos.escolhidosEm(context)
        fun ler(chave: String): String? = l.optString(chave).takeIf { it.isNotBlank() }?.let { Enderecos.normalizar(it) }
        if (l.has("canais")) {
            val canais = ler("canais")
            var c = Enderecos.canais
            if (em > local) {
                c = canais
            } else if (local == 0L) {
                // Aqui nunca se escolheu nada: fica o que o celular escolheu
                // (e um endereço que o celular não tem não apaga o daqui).
                if (canais != null) c = canais
            }
            if (c != Enderecos.canais) {
                Enderecos.definir(context, c, if (em > local) em else System.currentTimeMillis())
                Listas.recarregar(context)
            }
        }
        val minhas = l.optJSONArray("minhas") ?: return
        var novas = 0
        for (i in 0 until minhas.length()) {
            val m = minhas.optJSONObject(i) ?: continue
            val link = m.optString("link").trim()
            if (!(link.startsWith("http://") || link.startsWith("https://"))) continue
            if (MinhasListas.todas().any { it.link == link }) continue
            runCatching { MinhasListas.adicionar(m.optString("nome"), link, MinhasListas.baixar(link)) }
                .onSuccess { novas++ }
        }
        if (novas > 0) runCatching { Remote.refresh(context, forcar = false) }
    }

    /** Junta o que veio do celular ao que a TV tem. */
    suspend fun aplicar(context: Context, json: JSONObject) {
        val canais = json.optJSONArray("favCanais") ?: JSONArray()
        Favorites.load(context)
        for (i in 0 until canais.length()) {
            val nome = canais.optString(i).takeIf { it.isNotBlank() } ?: continue
            if (!Favorites.contains(nome)) Favorites.toggle(context, nome)
        }

        json.optJSONObject("listas")?.let { runCatching { aplicarListas(context, it) } }
    }
}
