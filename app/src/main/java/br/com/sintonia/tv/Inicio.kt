package br.com.sintonia.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load

/** Fileiras de canais e eventos. */
object Inicio {

    /// A fileira do último cartão focado: rolar a tela só quando ela muda.
    private var ultimaFileira = java.lang.ref.WeakReference<View>(null)

    /**
     * Um item da fileira.
     *
     * A ação vem junto de propósito: a fileira não sabe se aquilo é um filme,
     * uma série, um canal ou um atalho — ela desenha e chama.
     */
    data class Cartao(
        val titulo: String,
        /// Vazio quando não há imagem: aí a inicial preenche o quadro.
        val capa: String,
        val inicial: String,
        /// Quanto já foi visto, de 0 a 1. Nulo quando nunca foi aberto.
        val progresso: Float? = null,
        /// Linha de baixo do cartão largo (o programa no ar, no canal).
        val subtitulo: String? = null,
        val colagem: List<String> = emptyList(),
        /// Linha de cima e texto do destaque quando o cartão não é um título.
        val meta: String? = null,
        val sinopse: String? = null,
        /// Cartão de jogo: o selo do canto (placar ao vivo ou horário).
        val selo: String? = null,
        val seloAoVivo: Boolean = false,
        val aoEscolher: () -> Unit,
    )

    enum class Tipo { LARGO, EVENTO }

    /**
     * O gerente da lista de fileiras: não rola sozinho quando o foco muda.
     *
     * O padrão do RecyclerView rola até o cartão focado caber inteiro — e com
     * o respiro de baixo, o cartão da fileira de baixo nunca cabia, então cada
     * passo para o lado sacudia a tela inteira. Quem rola agora é o foco
     * trocar de fileira, e só ele.
     */
    class Filas(context: android.content.Context) : LinearLayoutManager(context) {
        override fun requestChildRectangleOnScreen(
            parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean,
        ) = false

        override fun requestChildRectangleOnScreen(
            parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean,
            focusedChildVisible: Boolean,
        ) = false
    }

    data class Fila(
        val titulo: String,
        val cartoes: List<Cartao>,
        val tipo: Tipo = Tipo.LARGO,
    )

    class Adapter(
        /// Chamado quando um cartão ganha o foco — é o que troca o destaque.
        private val aoFocar: ((Cartao) -> Unit)? = null,
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        private var filas: List<Fila> = emptyList()
        private val depositoLargos = RecyclerView.RecycledViewPool()
        private val depositoEventos = RecyclerView.RecycledViewPool()
        /// Onde cada fileira estava, para voltar no mesmo lugar.
        private val posicoes = mutableMapOf<String, Int>()

        /**
         * Troca as fileiras mexendo só no que mudou.
         *
         * Redesenhar tudo recriava as capas e o foco de quem estava navegando
         * escapava para o menu — acontecia quando as fileiras de gênero ou o
         * guia chegavam segundos depois da tela abrir.
         */
        fun trocar(novas: List<Fila>) {
            val velhas = filas
            filas = novas
            if (velhas.isEmpty()) { notifyDataSetChanged(); return }
            val comum = minOf(velhas.size, novas.size)
            for (i in 0 until comum) {
                val a = velhas[i]; val b = novas[i]
                val igual = a.titulo == b.titulo && a.tipo == b.tipo &&
                    a.cartoes.size == b.cartoes.size &&
                    a.cartoes.indices.all { a.cartoes[it].titulo == b.cartoes[it].titulo &&
                        a.cartoes[it].subtitulo == b.cartoes[it].subtitulo &&
                        a.cartoes[it].selo == b.cartoes[it].selo &&
                        a.cartoes[it].progresso == b.cartoes[it].progresso }
                if (!igual) notifyItemChanged(i)
            }
            if (novas.size > velhas.size) notifyItemRangeInserted(velhas.size, novas.size - velhas.size)
            else if (novas.size < velhas.size) notifyItemRangeRemoved(novas.size, velhas.size - novas.size)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val titulo: TextView = view.findViewById(R.id.filaTitulo)
            val capas: RecyclerView = view.findViewById(R.id.filaCapas)
        }

        override fun getItemViewType(position: Int) = filas[position].tipo.ordinal

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val holder = Holder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_fila, parent, false))
            holder.capas.layoutManager =
                LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false).apply {
                    initialPrefetchItemCount = 6
                }
            holder.capas.setRecycledViewPool(when (viewType) {
                Tipo.LARGO.ordinal -> depositoLargos
                else -> depositoEventos
            })
            holder.capas.isFocusable = false
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val fila = filas[position]
            holder.titulo.text = fila.titulo
            holder.capas.adapter = Capas(fila.cartoes, fila.tipo, aoFocar)
            holder.capas.scrollToPosition(posicoes[fila.titulo] ?: 0)
            holder.capas.clearOnScrollListeners()
            holder.capas.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val gerente = rv.layoutManager as? LinearLayoutManager ?: return
                    posicoes[fila.titulo] = gerente.findFirstVisibleItemPosition()
                }
            })
        }

        override fun getItemCount() = filas.size
    }

    /** As capas de uma fileira. */
    private class Capas(
        private val cartoes: List<Cartao>,
        private val tipo: Tipo,
        private val aoFocar: ((Cartao) -> Unit)?,
    ) : RecyclerView.Adapter<Capas.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val imagem: ImageView = view.findViewById(R.id.capaImagem)
            val inicial: TextView = view.findViewById(R.id.capaInicial)
            val nome: TextView = view.findViewById(R.id.capaNome)
            val sub: TextView? = view.findViewById(R.id.capaSub)
            val progresso: ProgressBar = view.findViewById(R.id.capaProgresso)
            val casa: ImageView? = view.findViewById(R.id.eventoCasa)
            val fora: ImageView? = view.findViewById(R.id.eventoFora)
            val liga: ImageView? = view.findViewById(R.id.eventoLiga)
            val selo: TextView? = view.findViewById(R.id.eventoSelo)
            var cartao: Cartao? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val layout = when (tipo) {
                Tipo.LARGO -> R.layout.item_cartao_largo
                Tipo.EVENTO -> R.layout.item_evento_cartao
            }
            val holder = Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
            Cartoes.arredondar(holder.imagem.parent as View)
            // Sem crescer no foco: o contorno do row_focus já mostra onde está,
            // e a capa aumentando empurrava as vizinhas a cada passo.
            holder.itemView.setOnFocusChangeListener { view, focado ->
                if (!focado) return@setOnFocusChangeListener
                holder.cartao?.let { aoFocar?.invoke(it) }
                // A fileira inteira à vista, com o nome dela — só quando o foco
                // chega de outra fileira. Andar para o lado não mexe na tela.
                val fileira = view.parent?.let { it as? View }?.parent as? View ?: return@setOnFocusChangeListener
                if (fileira === ultimaFileira.get()) return@setOnFocusChangeListener
                ultimaFileira = java.lang.ref.WeakReference(fileira)
                // A fileira nova sobe para o topo da lista, sempre no mesmo lugar.
                val lista = fileira.parent as? RecyclerView ?: return@setOnFocusChangeListener
                lista.post { lista.smoothScrollBy(0, fileira.top - lista.paddingTop) }
            }
            holder.itemView.setOnKeyListener { _, codigo, evento ->
                Cartoes.segurarNasPontas(holder, codigo, evento)
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val cartao = cartoes[position]
            holder.cartao = cartao
            holder.nome.text = cartao.titulo
            holder.sub?.text = cartao.subtitulo.orEmpty()
            holder.inicial.text = cartao.inicial
            holder.itemView.setOnClickListener { cartao.aoEscolher() }

            if (tipo == Tipo.EVENTO) { evento(holder, cartao); return }

            holder.progresso.visibility =
                if (cartao.progresso == null || cartao.progresso <= 0f) View.GONE else View.VISIBLE
            cartao.progresso?.let { holder.progresso.progress = (it * 1000).toInt() }

            Cartoes.carregar(holder.imagem, holder.inicial, cartao.capa.takeIf { it.isNotEmpty() })
        }

        /** Cartão de jogo: escudos (colagem = casa, fora, campeonato) e o selo. */
        private fun evento(holder: Holder, cartao: Cartao) {
            listOf(holder.casa, holder.fora, holder.liga).forEachIndexed { i, imagem ->
                imagem ?: return@forEachIndexed
                imagem.dispose()
                imagem.setImageDrawable(null)
                cartao.colagem.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { imagem.load(it) }
            }
            holder.selo?.apply {
                text = cartao.selo.orEmpty()
                visibility = if (cartao.selo.isNullOrEmpty()) View.GONE else View.VISIBLE
                setBackgroundResource(if (cartao.seloAoVivo) R.drawable.bg_chip else R.drawable.bg_chip_neutro)
            }
        }

        override fun getItemCount() = cartoes.size
    }
}
