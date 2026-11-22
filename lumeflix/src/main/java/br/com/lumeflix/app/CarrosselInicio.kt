package br.com.lumeflix.app

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi
internal class CarrosselInicio(
    tela: TelaComMenu,
    private val painel: Destaque,
    private val mostrar: (Inicio.Cartao) -> Unit,
) {
    private val banner = tela.findViewById<View>(R.id.inicioBanner)
    private val indicador = android.widget.TextView(tela).apply {
        setTextColor(android.graphics.Color.WHITE)
        textSize = 14f
        val margem = (24 * resources.displayMetrics.density).toInt()
        (banner as android.widget.FrameLayout).addView(this, android.widget.FrameLayout.LayoutParams(
            -2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
            marginEnd = margem
            bottomMargin = margem
        })
    }
    private fun exibirAtual() {
        val atual = itens.getOrNull(indice) ?: return
        indicador.text = "${indice + 1} / ${itens.size}"
        atual.alvo?.let { memoria.edit().putString(chave, it.nomeCompleto).apply() }
        mostrar(atual)
    }

    private var itens = emptyList<Inicio.Cartao>()
    private val memoria = tela.getSharedPreferences("destaque_recente", 0)
    private val chave = tela.javaClass.name + tela.intent.dataString.orEmpty() + tela.intent.getStringExtra("pagina.tipo").orEmpty()
    private var restaurar = memoria.getString(chave, null)
    private var indice = 0
    private var ultimaInteracao = android.os.SystemClock.elapsedRealtime()

    init {
        val filas = tela.findViewById<RecyclerView>(R.id.inicioFilas)
        filas.viewTreeObserver.addOnGlobalFocusChangeListener { _, foco ->
            ultimaInteracao = android.os.SystemClock.elapsedRealtime()
            if (foco == null || foco === filas) return@addOnGlobalFocusChangeListener
            val holder = filas.findContainingViewHolder(foco)
            val linha = holder?.bindingAdapterPosition ?: -1
            val params = banner.layoutParams as android.widget.LinearLayout.LayoutParams
            val total = (banner.parent as View).height
            if (linha <= 0 && total > 0) {
                // Reserve room for the whole focused shelf, including its heading and focus ring.
                val altura = if (linha == 0 && holder != null) {
                    val areaFila = holder.itemView.measuredHeight + filas.paddingTop + filas.paddingBottom
                    minOf(total * 3 / 4, (total - areaFila).coerceAtLeast(0))
                } else 0
                val peso = if (linha == 0) 0f else 3f
                if (params.height != altura || params.weight != peso) {
                    params.height = altura
                    params.weight = peso
                    banner.layoutParams = params
                }
                if (linha == 0) {
                    (filas.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                        ?.scrollToPositionWithOffset(0, 0)
                }
            }
            val visivel = linha <= 0
            if (visivel != (banner.visibility == View.VISIBLE)) {
                banner.visibility = if (visivel) View.VISIBLE else View.GONE
                if (visivel) exibirAtual() else painel.suspender()
            }
        }
        tela.lifecycleScope.launch {
            tela.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ultimaInteracao = android.os.SystemClock.elapsedRealtime()
                if (banner.visibility == View.VISIBLE) exibirAtual()
                while (true) {
                    delay(1_000)
                    if (banner.visibility == View.VISIBLE && !banner.hasFocus() && itens.size > 1 &&
                        android.os.SystemClock.elapsedRealtime() - ultimaInteracao >= 16_000) {
                        indice = (indice + 1) % itens.size
                        exibirAtual()
                        ultimaInteracao = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }
        }
    }

    fun atualizar(filas: List<Inicio.Fila>) {
        val candidatos = filas.sortedByDescending { it.titulo.contains("em alta", ignoreCase = true) }
            .asSequence().flatMap { it.cartoes.asSequence() }.filter { it.alvo != null }
            .distinctBy { it.alvo }.take(5).toList()
        if (candidatos.isEmpty() || candidatos.map { it.alvo } == itens.map { it.alvo }) return
        val anterior = itens.getOrNull(indice)
        val manter = anterior?.takeIf { banner.hasFocus() }
        itens = if (manter != null && candidatos.none { it.alvo == manter.alvo })
            (listOf(manter) + candidatos).take(5) else candidatos
        indice = itens.indexOfFirst { it.alvo?.nomeCompleto == (manter?.alvo?.nomeCompleto ?: restaurar ?: anterior?.alvo?.nomeCompleto) }.coerceAtLeast(0)
        restaurar = null
        ultimaInteracao = android.os.SystemClock.elapsedRealtime()
        if (banner.visibility == View.VISIBLE) exibirAtual()
    }
}
