package br.com.lumeflix.app

import android.view.KeyEvent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Fileiras de capa, como numa TV: a tela inicial, os resultados da busca.
 *
 * O peso foi o que mandou no desenho. Um TV Box de 2016 não aguenta dezenas de
 * imagens grandes:
 *
 * - as capas já chegam resolvidas (destaques, fichas), sem consulta ao TMDB;
 * - as fileiras dividem um só depósito de células;
 * - cada célula tem tamanho fixo, então nada pula enquanto as imagens chegam;
 * - as capas ficam em disco: rolar para baixo e voltar não baixa de novo.
 */
object Inicio {

    private val pedidosDeArte = Semaphore(3)

    internal fun indiceNaFilaVertical(indiceAtual: Int, totalDestino: Int): Int =
        if (totalDestino <= 0) -1 else if (indiceAtual in 0 until totalDestino) indiceAtual else 0

    /// A fileira do último cartão focado: rolar a tela só quando ela muda.
    private var ultimaFileira = java.lang.ref.WeakReference<View>(null)
    /** Invalida tentativas de foco pendentes ao apertar as setas rapidamente. */
    private val transicoesDeFoco = java.util.WeakHashMap<RecyclerView, Int>()

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
        /// Quando não veio capa pronta, diz se vale procurar uma e se é série.
        val procurarCapa: Boolean? = null,
        /// O nome com que se procura a capa, quando não é o que está no cartão.
        val nomeDaCapa: String? = null,
        /// Linha de baixo do cartão largo (o programa no ar, no canal).
        val subtitulo: String? = null,
        /** Trailer direto do banner, quando publicado no catálogo. */
        val trailer: String? = null,
        /// O título por trás do cartão, para o destaque do topo e o MENU.
        val alvo: Alvo? = null,
        /// MENU sobre o cartão: favoritar, tirar do "continuar"...
        val aoMenu: (() -> Unit)? = null,
        /// Cartão de categoria: as capas da colagem, o ícone e a cor dela.
        val colagem: List<String> = emptyList(),
        val icone: Int = 0,
        val cor: Int = 0,
        /// Linha de cima e texto do destaque quando o cartão não é um título.
        val meta: String? = null,
        val sinopse: String? = null,
        /// Título cuja imagem larga vai ao fundo do destaque, quando o cartão
        /// não é ele mesmo um título (a categoria mostra o filme em alta).
        val fundoDe: Alvo? = null,
        /** Backdrop TMDB já resolvido enquanto o título estava em foco. */
        var capaLarga: String? = null,
        /** Atualiza a imagem do holder ainda ligado a este cartão. */
        var atualizarCapaLarga: ((String) -> Unit)? = null,
        /// Cartão de jogo: o selo do canto (placar ao vivo ou horário).
        val selo: String? = null,
        val seloAoVivo: Boolean = false,
        val ranking: Int? = null,
        val aoEscolher: () -> Unit,
    )

    enum class Tipo { CAPA, LARGO, SECAO, EVENTO, PAISAGEM, RANKING }

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
        val tipo: Tipo = Tipo.CAPA,
        /// Se as fichas já estavam na memória quando a fileira foi montada: a
        /// capa de quem não traz a própria sai delas na hora de desenhar, e a
        /// fileira montada antes delas tem de ser redesenhada quando chegam.
        val comFichas: Boolean = Generos.prontos,
    )

    private val seriesNovas = Regex("""series novas(?: de)?(?: \d{4})?""")

    fun organizarCatalogo(filas: List<Fila>, nomeContinuar: String): List<Fila> {
        val disponiveis = filas.filter { it.cartoes.isNotEmpty() }
        val nomes = disponiveis.map { Vod.normalizar(it.titulo) }.toSet()
        val continuar = disponiveis.firstOrNull { it.titulo == nomeContinuar }
        val vistos = mutableSetOf<String>()
        val resultado = disponiveis.filter { fila ->
            val nome = Vod.normalizar(fila.titulo)
            fila.titulo != nomeContinuar &&
                !(( "em alta hoje" in nomes || nomes.any { it.startsWith("top 10") }) && nome in setOf("em alta", "series em alta", "filmes em alta")) &&
                !("novas series" in nomes && seriesNovas.matches(nome)) &&
                vistos.add(nome)
        }.toMutableList()
        for (i in resultado.indices) {
            val fila = resultado[i]
            if (fila.titulo.startsWith("Top 10") || fila.titulo == "Em alta hoje") {
                val serie = fila.cartoes.first().alvo?.serie
                val reservas = disponiveis.asSequence()
                    .filter { Vod.normalizar(it.titulo) in setOf("em alta", "filmes em alta", "series em alta") }
                    .flatMap { it.cartoes.asSequence() }
                    .filter { serie != null && it.alvo?.serie == serie }
                val classificados = (fila.cartoes.asSequence() + reservas)
                    .distinctBy { c ->
                        val a = c.alvo
                        val id = a?.tmdbId?.takeIf { it > 0 } ?: a?.let { Generos.id(it.titulo, it.serie) }
                        if (id != null) "id:$id" else Vod.normalizar(a?.nomeCompleto ?: c.titulo)
                    }
                    .take(10).mapIndexed { pos, c -> c.copy(ranking = pos + 1) }.toList()
                resultado[i] = fila.copy(tipo = Tipo.RANKING, cartoes = classificados)
            }
        }
        if (continuar != null) {
            val rankings = resultado.filter { it.tipo == Tipo.RANKING }
            resultado.removeAll { it.tipo == Tipo.RANKING }
            val pos = minOf(1, resultado.size)
            resultado.add(pos, continuar)
            resultado.addAll(pos + 1, rankings)
        }
        val sugestao = resultado.firstOrNull { it.titulo.startsWith("Porque você viu ") }
        if (sugestao != null) {
            resultado.remove(sugestao)
            resultado.add(minOf(3, resultado.size), sugestao)
        }
        return resultado
    }

    class Adapter(
        private val escopo: kotlinx.coroutines.CoroutineScope? = null,
        private val capaDe: (suspend (String, Boolean) -> String?)? = null,
        /// Chamado quando um cartão ganha o foco — é o que troca o destaque.
        private val aoFocar: ((Cartao) -> Unit)? = null,
        /// Fileiras de catálogo do LumeFlix usam artes 16:9; TV/radio mantêm os layouts próprios.
        private val paisagem: Boolean = false,
    ) : RecyclerView.Adapter<Adapter.Holder>() {

        private var filas: List<Fila> = emptyList()
        private var listaExibida: RecyclerView? = null

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            listaExibida = recyclerView
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            listaExibida = null
        }
        private val depositoCapas = RecyclerView.RecycledViewPool()
        private val depositoLargos = RecyclerView.RecycledViewPool()
        private val depositoRanking = RecyclerView.RecycledViewPool()
        private val depositoPaisagens = RecyclerView.RecycledViewPool()
        private val depositoSecoes = RecyclerView.RecycledViewPool()
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
            val vertical = listaExibida
            val linha = vertical?.findFocus()?.let { vertical.findContainingViewHolder(it) }
            val visivel = linha?.bindingAdapterPosition?.let(filas::getOrNull)
            val offset = linha?.itemView?.top?.minus(vertical.paddingTop)
            val velhas = filas
            filas = novas
            if (velhas.isEmpty()) { notifyDataSetChanged(); return }
            androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = velhas.size
                override fun getNewListSize() = novas.size
                override fun areItemsTheSame(old: Int, new: Int) =
                    velhas[old].titulo == novas[new].titulo && velhas[old].tipo == novas[new].tipo
                override fun areContentsTheSame(old: Int, new: Int) = velhas[old] === novas[new]
            }).dispatchUpdatesTo(this)
            if (visivel != null && offset != null) {
                val pos = novas.indexOfFirst { it.titulo == visivel.titulo && it.tipo == visivel.tipo }
                if (pos >= 0) (vertical.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, offset)
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val titulo: TextView = view.findViewById(R.id.filaTitulo)
            val capas: RecyclerView = view.findViewById(R.id.filaCapas)
        }

        private fun tipoVisual(position: Int): Tipo =
            if (paisagem && filas[position].tipo == Tipo.CAPA) Tipo.PAISAGEM else filas[position].tipo

        override fun getItemViewType(position: Int) = tipoVisual(position).ordinal

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val holder = Holder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_fila, parent, false))
            holder.capas.layoutManager =
                LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false).apply {
                    initialPrefetchItemCount = 2
                }
            holder.capas.setRecycledViewPool(when (viewType) {
                Tipo.LARGO.ordinal -> depositoLargos
                Tipo.SECAO.ordinal -> depositoSecoes
                Tipo.EVENTO.ordinal -> depositoEventos
                Tipo.RANKING.ordinal -> depositoRanking
                Tipo.PAISAGEM.ordinal -> depositoPaisagens
                else -> depositoCapas
            })
            holder.capas.isFocusable = false
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val fila = filas[position]
            val anterior = holder.capas.adapter as? Capas
            if (holder.titulo.text == fila.titulo && anterior != null) {
                anterior.atualizar(fila.cartoes)
                return
            }
            holder.titulo.text = fila.titulo
            holder.capas.adapter = Capas(fila.cartoes, tipoVisual(position), escopo, capaDe, aoFocar)
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
        private var cartoes: List<Cartao>,
        private val tipo: Tipo,
        private val escopo: kotlinx.coroutines.CoroutineScope?,
        private val capaDe: (suspend (String, Boolean) -> String?)?,
        private val aoFocar: ((Cartao) -> Unit)?,
    ) : RecyclerView.Adapter<Capas.Holder>() {

        fun atualizar(novos: List<Cartao>) {
            val antigos = cartoes
            if (antigos === novos) return
            cartoes = novos
            androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = antigos.size
                override fun getNewListSize() = novos.size
                override fun areItemsTheSame(old: Int, new: Int) =
                    antigos[old].alvo == novos[new].alvo && antigos[old].titulo == novos[new].titulo
                override fun areContentsTheSame(old: Int, new: Int) = antigos[old] === novos[new]
            }, false).dispatchUpdatesTo(this)
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val imagem: ImageView = view.findViewById(R.id.capaImagem)
            val inicial: TextView = view.findViewById(R.id.capaInicial)
            val nome: TextView = view.findViewById(R.id.capaNome)
            val logo: ImageView? = view.findViewById(R.id.capaLogo)
            var arteJob: Job? = null
            val sub: TextView? = view.findViewById(R.id.capaSub)
            val progresso: ProgressBar = view.findViewById(R.id.capaProgresso)
            val colagem: List<ImageView> = listOfNotNull(
                view.findViewById(R.id.secaoCapa1), view.findViewById(R.id.secaoCapa2),
                view.findViewById(R.id.secaoCapa3), view.findViewById(R.id.secaoCapa4))
            val icone: ImageView? = view.findViewById(R.id.secaoIcone)
            val cor: View? = view.findViewById(R.id.secaoCor)
            val casa: ImageView? = view.findViewById(R.id.eventoCasa)
            val fora: ImageView? = view.findViewById(R.id.eventoFora)
            val liga: ImageView? = view.findViewById(R.id.eventoLiga)
            val selo: TextView? = view.findViewById(R.id.eventoSelo)
            var pedido: String? = null
            var cartao: Cartao? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val layout = when (tipo) {
                Tipo.LARGO -> R.layout.item_cartao_largo
                Tipo.RANKING -> R.layout.item_ranking
                Tipo.PAISAGEM -> R.layout.item_cartao_paisagem
                Tipo.SECAO -> R.layout.item_secao
                Tipo.EVENTO -> R.layout.item_evento_cartao
                else -> R.layout.item_capa
            }
            val holder = Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
            if (tipo == Tipo.RANKING) {
                holder.itemView.findViewById<TextView>(R.id.capaRanking).paint.apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = parent.resources.displayMetrics.density * 1.4f
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
            }
            Cartoes.arredondar(holder.imagem.parent as View)
            // Sem crescer no foco: o contorno do row_focus já mostra onde está,
            // e a capa aumentando empurrava as vizinhas a cada passo.
            holder.itemView.setOnFocusChangeListener { view, focado ->
                if (!BuildConfig.TV_AO_VIVO) {
                    view.animate().scaleX(if (focado) 1.025f else 1f)
                        .scaleY(if (focado) 1.025f else 1f).setDuration(140).start()
                    view.translationZ = if (focado) 12f else 0f
                }
                if (!focado) return@setOnFocusChangeListener
                holder.cartao?.let { aoFocar?.invoke(it) }
                // A fileira inteira à vista, com o nome dela — só quando o foco
                // chega de outra fileira. Andar para o lado não mexe na tela.
                val fileira = view.parent?.let { it as? View }?.parent as? View ?: return@setOnFocusChangeListener
                if (fileira === ultimaFileira.get()) return@setOnFocusChangeListener
                ultimaFileira = java.lang.ref.WeakReference(fileira)
                // A fileira nova sobe para o topo da lista, sempre no mesmo lugar.
                val lista = fileira.parent as? RecyclerView ?: return@setOnFocusChangeListener
                lista.post {
                    // O Runnable pode executar depois que outra fileira ganhou
                    // foco. Não rolar uma fileira antiga para cima de novo.
                    if (ultimaFileira.get() !== fileira || fileira.parent !== lista) return@post
                    val deslocamento = fileira.top - lista.paddingTop
                    if (deslocamento != 0) lista.smoothScrollBy(0, deslocamento)
                }
            }
            holder.itemView.setOnKeyListener { _, codigo, evento ->
                if (Cartoes.segurarNasPontas(holder, codigo, evento)) return@setOnKeyListener true
                if (evento.action == KeyEvent.ACTION_DOWN &&
                    (codigo == KeyEvent.KEYCODE_DPAD_UP || codigo == KeyEvent.KEYCODE_DPAD_DOWN) &&
                    moverVertical(holder, if (codigo == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)) {
                    return@setOnKeyListener true
                }
                if (evento.action == KeyEvent.ACTION_DOWN &&
                    (codigo == KeyEvent.KEYCODE_MENU || codigo == KeyEvent.KEYCODE_BOOKMARK)) {
                    holder.cartao?.aoMenu?.let { it(); true } ?: false
                } else false
            }
            holder.itemView.setOnLongClickListener { holder.cartao?.aoMenu?.let { it(); true } ?: false }
            return holder
        }

        /** Mantém a coluna ao trocar de fileira; o foco espacial escolhia a última capa. */
        private fun moverVertical(holder: Holder, passo: Int): Boolean {
            val horizontal = holder.itemView.parent as? RecyclerView ?: return false
            val fileira = horizontal.parent as? View ?: return false
            val vertical = fileira.parent as? RecyclerView ?: return false
            val atual = vertical.getChildAdapterPosition(fileira)
            val destino = atual + passo
            val total = vertical.adapter?.itemCount ?: return false
            if (atual == RecyclerView.NO_POSITION || destino !in 0 until total) return false
            val transicao = (transicoesDeFoco[vertical] ?: 0) + 1
            transicoesDeFoco[vertical] = transicao
            focarNaFileira(vertical, destino, holder.bindingAdapterPosition, transicao)
            return true
        }

        /**
         * Se o cartão de destino ainda não existe, estaciona o foco NO
         * RecyclerView antes do scroll. Sem isso, a reciclagem do cartão atual
         * faz o Android TV focar brevemente a primeira aba do menu ("Início"),
         * gerando a piscada amarela ao navegar entre as categorias.
         */
        private fun manterFocoNoCatalogo(vertical: RecyclerView) {
            if (BuildConfig.TV_AO_VIVO || vertical.isFocused) return
            vertical.isFocusable = true
            val anterior = vertical.descendantFocusability
            vertical.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            vertical.requestFocus()
            vertical.descendantFocusability = anterior
        }

        private fun focarNaFileira(
            vertical: RecyclerView, fila: Int, colunaAtual: Int,
            transicao: Int, tentativas: Int = 12,
        ) {
            if (transicoesDeFoco[vertical] != transicao) return
            val linha = vertical.findViewHolderForAdapterPosition(fila)?.itemView
            val horizontal = linha?.findViewById<RecyclerView>(R.id.filaCapas)
            if (horizontal == null) {
                manterFocoNoCatalogo(vertical)
                vertical.scrollToPosition(fila)
                if (tentativas > 0) vertical.postDelayed({
                    focarNaFileira(vertical, fila, colunaAtual, transicao, tentativas - 1)
                }, 16)
                return
            }
            val coluna = indiceNaFilaVertical(colunaAtual, horizontal.adapter?.itemCount ?: 0)
            if (coluna < 0) return
            val alvo = horizontal.findViewHolderForAdapterPosition(coluna)?.itemView
            if (alvo?.requestFocus() == true) return
            manterFocoNoCatalogo(vertical)
            horizontal.scrollToPosition(coluna)
            if (tentativas > 0) horizontal.postDelayed({
                focarNaFileira(vertical, fila, colunaAtual, transicao, tentativas - 1)
            }, 16)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val cartao = cartoes[position]
            holder.arteJob?.cancel()
            holder.logo?.dispose()
            holder.logo?.setImageDrawable(null)
            holder.logo?.visibility = View.INVISIBLE
            holder.nome.visibility =
                if (!BuildConfig.TV_AO_VIVO && tipo == Tipo.PAISAGEM && cartao.alvo != null && escopo != null && holder.logo != null) View.INVISIBLE else View.VISIBLE
            holder.itemView.contentDescription = cartao.titulo
            holder.itemView.animate().cancel()
            val escala = if (!BuildConfig.TV_AO_VIVO && holder.itemView.hasFocus()) 1.025f else 1f
            holder.itemView.scaleX = escala
            holder.itemView.scaleY = escala
            holder.itemView.translationZ = if (escala > 1f) 12f else 0f
            holder.cartao?.let { anterior ->
                if (anterior !== cartao) anterior.atualizarCapaLarga = null
            }
            holder.cartao = cartao
            holder.itemView.findViewById<TextView>(R.id.capaRanking)?.apply {
                visibility = if (cartao.ranking != null) View.VISIBLE else View.GONE
                text = cartao.ranking?.toString()
            }
            holder.nome.text = cartao.titulo
            if (tipo == Tipo.RANKING && cartao.capa.isNotBlank()) holder.nome.visibility = View.GONE
            holder.sub?.text = cartao.subtitulo.orEmpty()
            holder.inicial.text = cartao.inicial
            holder.itemView.setOnClickListener {
                FichaActivity.arteSelecionada = cartao.alvo?.let { it to holder.imagem.drawable?.constantState }
                try { cartao.aoEscolher() } finally { FichaActivity.arteSelecionada = null }
            }

            if (tipo == Tipo.SECAO) { secao(holder, cartao); return }
            if (tipo == Tipo.EVENTO) { evento(holder, cartao); return }

            holder.progresso.visibility =
                if (cartao.progresso == null || cartao.progresso <= 0f) View.GONE else View.VISIBLE
            cartao.progresso?.let { holder.progresso.progress = (it * 1000).toInt() }

            if (holder.pedido != cartao.titulo) {
                holder.imagem.tag = null
                holder.imagem.dispose()
                holder.imagem.setImageDrawable(null)
            }
            holder.inicial.visibility = if (BuildConfig.TV_AO_VIVO) View.VISIBLE else View.INVISIBLE
            holder.pedido = cartao.titulo
            if (tipo == Tipo.PAISAGEM) {
                cartao.alvo?.let { alvo ->
                    holder.arteJob = escopo?.launch(semDerrubar) {
                        val ficha = pedidosDeArte.withPermit {
                            Detalhes.de(alvo.titulo, alvo.serie, alvo.tmdbId.takeIf { it > 0 })
                        }
                        if (holder.cartao !== cartao) return@launch
                        if (ficha?.logo.isNullOrBlank()) holder.nome.visibility = View.VISIBLE
                        if (ficha == null || holder.cartao !== cartao) return@launch
                        ficha.fundo?.let { url ->
                            cartao.capaLarga = url
                            carregarPaisagem(holder, cartao, url)
                        }
                        ficha.logo?.let { url ->
                            holder.logo?.load(url) {
                                listener(onError = { _, _ ->
                                    if (holder.cartao === cartao) holder.nome.visibility = View.VISIBLE
                                }, onSuccess = { _, _ ->
                                    if (holder.cartao === cartao) {
                                        holder.logo?.visibility = View.VISIBLE
                                        holder.nome.visibility = View.GONE
                                    }
                                })
                            }
                        }
                    }
                }
                cartao.atualizarCapaLarga = { url ->
                    if (holder.cartao === cartao && holder.pedido == cartao.titulo) {
                        carregarPaisagem(holder, cartao, url)
                    }
                }
                cartao.capaLarga?.let {
                    carregarPaisagem(holder, cartao, it)
                    return
                }
            } else {
                cartao.atualizarCapaLarga = null
            }
            val direta = cartao.capa.ifEmpty {
                // Sem capa pronta: o arquivo de fichas quase sempre sabe, e em
                // memória — sem ir à rede.
                val serie = cartao.procurarCapa
                if (serie != null) Generos.capa(cartao.nomeDaCapa ?: cartao.titulo, serie).orEmpty() else ""
            }
            if (direta.isEmpty()) {
                holder.imagem.visibility = View.GONE
                val serie = cartao.procurarCapa ?: return
                val buscar = capaDe ?: return
                val nome = cartao.nomeDaCapa ?: cartao.titulo
                escopo?.launch {
                    val achada = buscar(nome, serie) ?: return@launch
                    if (holder.pedido != cartao.titulo) return@launch
                    if (tipo == Tipo.PAISAGEM) carregarPaisagem(holder, cartao, achada)
                    else Cartoes.carregar(holder.imagem, holder.inicial, achada)
                }
                return
            }
            if (tipo == Tipo.PAISAGEM) carregarPaisagem(holder, cartao, direta)
            else Cartoes.carregar(holder.imagem, holder.inicial, direta)
        }

        private fun carregarPaisagem(holder: Holder, cartao: Cartao, url: String) {
            if (holder.cartao === cartao) Cartoes.carregar(holder.imagem, holder.inicial, url)
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

        /** Cartão de categoria: colagem de capas, cor e ícone da categoria. */
        private fun secao(holder: Holder, cartao: Cartao) {
            holder.colagem.forEachIndexed { i, imagem ->
                imagem.dispose()
                imagem.setImageDrawable(null)
                cartao.colagem.getOrNull(i)?.let { imagem.load(it) }
            }
            // A cor entra pelo canto de baixo e some antes do meio do cartão.
            holder.cor?.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BL_TR,
                intArrayOf(cartao.cor and 0x00FFFFFF or (0xB3 shl 24), cartao.cor and 0x00FFFFFF),
            )
            holder.icone?.apply {
                setImageResource(cartao.icone)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(cartao.cor)
                }
                imageTintList = android.content.res.ColorStateList.valueOf(0xFF0B0E14.toInt())
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.arteJob?.cancel()
            holder.logo?.dispose()
            holder.cartao?.atualizarCapaLarga = null
            holder.cartao = null
            super.onViewRecycled(holder)
        }

        override fun getItemCount() = cartoes.size
    }
}
