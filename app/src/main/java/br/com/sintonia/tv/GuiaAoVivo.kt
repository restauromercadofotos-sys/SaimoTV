package br.com.sintonia.tv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menu do controle sobre o vídeo, com o foco controlado pelo direcional. */
internal class GuiaAoVivo(
    private val raiz: ViewGroup,
    private val numero: (Channel) -> Int,
    private val sintonizar: (String) -> Unit,
    private val favoritar: (String) -> Unit,
    private val abrirEsportes: () -> Unit,
) {
    private val contexto = raiz.context
    private val formatoDia = SimpleDateFormat("EEE, dd/MM", Locale.forLanguageTag("pt-BR"))
    private val hora = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR"))
    private val painel = LinearLayout(contexto).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(20))
        setBackgroundColor(0xdd101716.toInt())
        isFocusableInTouchMode = true
        visibility = View.GONE
    }
    private val corpo = LinearLayout(contexto).apply { orientation = LinearLayout.HORIZONTAL }
    private val abas = LinearLayout(contexto).apply { orientation = LinearLayout.HORIZONTAL }
    private val abaCanais = texto(15f)
    private val abaEsportes = texto(15f)
    private var focoNasAbas = false
    private var esporteFocado = false

    private var categorias = listOf("Todos", Categorias.FAVORITOS)
    private var catalogo = emptyList<Channel>()
    private var canais = emptyList<Channel>()
    private var dias = emptyList<String>()
    private var grade = emptyList<Programme>()
    private var programas = emptyList<Programme>()
    private val secoes = Coluna(0, 0.85f)
    private val lista = Coluna(1, 1.10f)
    private val datas = Coluna(2, 0.46f)
    private val guia = Coluna(3, 1.80f)
    private var coluna = lista
    private val ocultar = Runnable { fechar() }

    val aberto: Boolean get() = painel.visibility == View.VISIBLE

    init {
        painel.addView(abas, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
        painel.addView(corpo, LinearLayout.LayoutParams(-1, 0, 1f))
        abaCanais.setText(R.string.guia_canais)
        abaEsportes.setText(R.string.menu_eventos)
        for (aba in listOf(abaCanais, abaEsportes)) {
            aba.gravity = Gravity.CENTER
            aba.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            aba.setPadding(dp(20), 0, dp(20), 0)
            abas.addView(aba, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = dp(8) })
        }
        abaCanais.setOnClickListener { focoNasAbas = false; atualizarFoco(); atividade() }
        abaEsportes.setOnClickListener { fechar(); abrirEsportes() }
        raiz.addView(painel, ViewGroup.LayoutParams(-1, -1))
    }

    fun abrir(catalogo: List<Channel>, atual: Channel?) {
        painel.bringToFront()
        painel.visibility = View.VISIBLE
        painel.requestFocus()
        focoNasAbas = false
        esporteFocado = false
        secoes.posicao = 0
        coluna = lista
        atualizar(catalogo, atual?.name)
        atividade()
    }

    fun fechar() {
        painel.removeCallbacks(ocultar)
        painel.visibility = View.GONE
    }

    fun atualizar(novoCatalogo: List<Channel> = catalogo, nome: String? = canais.getOrNull(lista.posicao)?.name) {
        if (!aberto) return
        val dia = dias.getOrNull(datas.posicao)
        val programa = programas.getOrNull(guia.posicao)?.start
        val categoria = categorias.getOrNull(secoes.posicao)
        catalogo = novoCatalogo.sortedBy(numero)
        categorias = listOf("Todos", Categorias.FAVORITOS) + catalogo.map(Categorias::de)
            .distinct().filterNot { it == Categorias.FAVORITOS }
        secoes.posicao = categorias.indexOf(categoria).coerceAtLeast(0)
        filtrar(nome, dia, programa)
    }

    private fun atividade() {
        painel.removeCallbacks(ocultar)
        painel.postDelayed(ocultar, 8_000)
    }

    fun tecla(codigo: Int): Boolean {
        if (!aberto) return false
        if (focoNasAbas) {
            when (codigo) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> fechar()
                KeyEvent.KEYCODE_DPAD_LEFT -> esporteFocado = false
                KeyEvent.KEYCODE_DPAD_RIGHT -> esporteFocado = true
                KeyEvent.KEYCODE_DPAD_DOWN -> focoNasAbas = false
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (esporteFocado) { fechar(); abrirEsportes(); return true }
                    focoNasAbas = false
                }
                KeyEvent.KEYCODE_DPAD_UP -> Unit
                else -> return false
            }
            if (aberto) { atualizarFoco(); atividade() }
            return true
        }
        when (codigo) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> fechar()
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (coluna == secoes) entrarNasAbas()
                coluna = when (coluna) {
                    guia -> datas
                    datas -> lista
                    lista -> secoes
                    else -> secoes
                }
                atualizarFoco()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                coluna = when (coluna) {
                    secoes -> lista
                    lista -> if (dias.isEmpty()) lista else datas
                    datas -> guia
                    else -> guia
                }
                atualizarFoco()
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (coluna.posicao == 0) entrarNasAbas() else mover(coluna, coluna.posicao - 1)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> mover(coluna, coluna.posicao + 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> confirmar(coluna)
            KeyEvent.KEYCODE_MENU -> canais.getOrNull(lista.posicao)?.let { favoritar(it.name) }
            else -> return false
        }
        if (aberto) atividade()
        return true
    }

    private fun confirmar(alvo: Coluna) {
        when (alvo) {
            secoes -> coluna = lista
            lista -> canais.getOrNull(lista.posicao)?.let {
                fechar()
                sintonizar(it.name)
            }
            datas -> coluna = guia
        }
        atualizarFoco()
    }

    private fun mover(alvo: Coluna, posicao: Int) {
        if (posicao !in alvo.itens.indices || posicao == alvo.posicao) return
        val anterior = alvo.posicao
        alvo.posicao = posicao
        alvo.adapter.notifyItemChanged(anterior)
        alvo.adapter.notifyItemChanged(posicao)
        alvo.lista.scrollToPosition(posicao)
        when (alvo) {
            secoes -> filtrar()
            lista -> montarDatas()
            datas -> montarProgramas()
            guia -> detalhe()
        }
    }

    private fun entrarNasAbas() {
        focoNasAbas = true
        esporteFocado = false
        atualizarFoco()
    }

    private fun atualizarFoco() {
        for (aba in listOf(abaCanais, abaEsportes)) {
            val ativa = focoNasAbas && (aba == abaEsportes) == esporteFocado
            aba.setTextColor(if (ativa) 0xff10221a.toInt() else Color.WHITE)
            aba.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (ativa) 0xff73f2be.toInt() else if (aba == abaCanais) 0xff263c34.toInt() else Color.TRANSPARENT)
            }
        }
        secoes.caixa.visibility = if (coluna == secoes) View.VISIBLE else View.GONE
        datas.caixa.visibility = if (coluna == secoes || dias.isEmpty()) View.GONE else View.VISIBLE
        guia.caixa.visibility = if (coluna == secoes) View.GONE else View.VISIBLE
        detalhe()
        for (painel in listOf(secoes, lista, datas, guia)) {
            if (painel.posicao in painel.itens.indices) painel.adapter.notifyItemChanged(painel.posicao)
            painel.lista.scrollToPosition(painel.posicao)
        }
    }

    private fun filtrar(nome: String? = null, dia: String? = null, programa: Long? = null) {
        val categoria = categorias[secoes.posicao]
        canais = when (secoes.posicao) {
            0 -> catalogo
            1 -> catalogo.filter { Favorites.contains(it.name) }
            else -> catalogo.filter { Categorias.de(it) == categoria }
        }
        secoes.mostrar("Categorias", categorias.map { Linha(it) }, secoes.posicao)
        lista.mostrar(categoria, canais.map { Linha(it.name, numero = numero(it).toString().padStart(3, '0'), logo = it.logo) },
            canais.indexOfFirst { it.name == nome })
        montarDatas(dia, programa)
    }

    private fun montarDatas(dia: String? = null, programa: Long? = null) {
        lista.titulo.text = "${categorias[secoes.posicao]}   ·   ${if (canais.isEmpty()) 0 else lista.posicao + 1}/${canais.size}"
        val nome = canais.getOrNull(lista.posicao)?.name
        grade = nome?.let(Epg::schedule).orEmpty()
        val hoje = formatoDia.format(Date())
        dias = grade.map { formatoDia.format(Date(it.start)) }.distinct()
        datas.mostrar("Dias", dias.map { Linha(if (it == hoje) "Hoje" else it) }, dias.indexOf(dia).takeIf { it >= 0 } ?: dias.indexOf(hoje))
        if (dias.isEmpty() && (coluna == datas || coluna == guia)) coluna = lista
        montarProgramas(programa)
        atualizarFoco()
    }

    private fun montarProgramas(inicio: Long? = null) {
        val dia = dias.getOrNull(datas.posicao)
        programas = grade.filter { formatoDia.format(Date(it.start)) == dia }
        val agora = System.currentTimeMillis()
        guia.mostrar("Programação", programas.map {
            Linha(it.title, horario = horario(it), noAr = it.isOnAir(agora))
        }, programas.indexOfFirst { it.start == inicio }.takeIf { it >= 0 }
            ?: programas.indexOfFirst { it.isOnAir(agora) }.takeIf { it >= 0 }
            ?: programas.indexOfFirst { it.start >= agora })
        detalhe()
    }

    private fun horario(programa: Programme) =
        "${hora.format(Date(programa.start))} – ${hora.format(Date(programa.stop))}"

    private fun detalhe() {
        val programa = programas.getOrNull(guia.posicao)
        guia.detalheTitulo.text = programa?.title ?: "Programação indisponível"
        val sinopse = programa?.description?.trim().orEmpty()
        guia.detalhe.text = sinopse
        guia.detalheTitulo.visibility = if (programa == null || coluna == guia) View.VISIBLE else View.GONE
        guia.detalhe.visibility = if (coluna == guia && sinopse.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(valor: Int) = (valor * contexto.resources.displayMetrics.density).toInt()

    private fun texto(tamanho: Float, cor: Int = Color.WHITE) = TextView(contexto).apply {
        textSize = tamanho
        includeFontPadding = false
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(cor)
        ellipsize = TextUtils.TruncateAt.END
    }

    private data class Linha(
        val texto: String,
        val numero: String = "",
        val logo: String? = null,
        val horario: String = "",
        val noAr: Boolean = false,
    )

    private inner class Coluna(private val id: Int, peso: Float) {
        val caixa = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val titulo = texto(13f, 0xff73f2be.toInt()).apply {
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
        val detalheTitulo = texto(16f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        val detalhe = texto(13f, 0xffbaccc6.toInt()).apply {
            maxLines = 3
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(12), 0, dp(12), dp(12))
            visibility = View.GONE
        }
        val lista = RecyclerView(contexto).apply {
            layoutManager = LinearLayoutManager(contexto)
            itemAnimator = null
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        var itens = emptyList<Linha>()
        var posicao = 0
        val adapter = object : RecyclerView.Adapter<LinhaHolder>() {
            override fun getItemCount() = itens.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LinhaHolder(id == 1, id == 3)
            override fun onBindViewHolder(holder: LinhaHolder, position: Int) {
                val item = itens[position]
                val selecionado = position == posicao
                holder.raiz.isSelected = selecionado
                val ativo = selecionado && !focoNasAbas && coluna == this@Coluna
                holder.raiz.background = when {
                    ativo -> holder.foco
                    selecionado -> holder.selecao
                    else -> null
                }
                val cor = if (ativo) 0xff10221a.toInt() else Color.WHITE
                val secundaria = if (ativo) 0xff234839.toInt() else 0xffb2c6bd.toInt()
                holder.titulo.text = item.texto
                holder.titulo.setTextColor(cor)
                holder.horario.text = item.horario
                holder.horario.setTextColor(secundaria)
                holder.aoVivo.visibility = if (item.noAr) View.VISIBLE else View.GONE
                holder.aoVivo.setTextColor(if (ativo) 0xff10221a.toInt() else 0xff73f2be.toInt())
                holder.numero.text = item.numero
                holder.numero.setTextColor(secundaria)
                if (id == 1) holder.logo.load(item.logo)
                holder.raiz.setOnClickListener {
                    val indice = holder.bindingAdapterPosition
                    if (indice == RecyclerView.NO_POSITION) return@setOnClickListener
                    coluna = this@Coluna
                    mover(this@Coluna, indice)
                    confirmar(this@Coluna)
                    if (aberto) atividade()
                }
            }
        }

        init {
            corpo.addView(caixa, LinearLayout.LayoutParams(0, -1, peso))
            caixa.addView(titulo, LinearLayout.LayoutParams(-1, dp(32)))
            caixa.addView(detalheTitulo, LinearLayout.LayoutParams(-1, -2))
            caixa.addView(detalhe, LinearLayout.LayoutParams(-1, -2))
            caixa.addView(lista, LinearLayout.LayoutParams(-1, 0, 1f))
            lista.adapter = adapter
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun mostrar(cabecalho: String, linhas: List<Linha>, inicio: Int) {
            titulo.text = cabecalho
            itens = linhas
            posicao = inicio.coerceIn(0, linhas.lastIndex.coerceAtLeast(0))
            adapter.notifyDataSetChanged()
            lista.scrollToPosition(posicao)
        }
    }

    private inner class LinhaHolder(comLogo: Boolean, comHorario: Boolean) :
        RecyclerView.ViewHolder(LinearLayout(contexto)) {
        val raiz = itemView as LinearLayout
        val foco = GradientDrawable().apply { setColor(0xff73f2be.toInt()); cornerRadius = dp(6).toFloat() }
        val selecao = GradientDrawable().apply { setColor(0xff263c34.toInt()); cornerRadius = dp(6).toFloat() }
        val titulo = texto(if (comHorario) 15f else 16f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = if (comHorario) 2 else 1
        }
        val horario = texto(12f).apply { maxLines = 1; fontFeatureSettings = "'tnum'" }
        val aoVivo = texto(11f).apply { text = "Ao vivo"; maxLines = 1 }
        val numero = texto(12f).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            fontFeatureSettings = "'tnum'"
        }
        val logo = ImageView(contexto).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

        init {
            raiz.layoutParams = RecyclerView.LayoutParams(-1, dp(if (comHorario) 52 else 40))
            raiz.gravity = Gravity.CENTER_VERTICAL
            raiz.setPadding(dp(12), 0, dp(12), 0)
            if (comLogo) {
                raiz.addView(numero, LinearLayout.LayoutParams(dp(26), -1).apply { marginEnd = dp(10) })
                raiz.addView(logo, LinearLayout.LayoutParams(dp(32), dp(26)).apply { marginEnd = dp(10) })
            }
            if (comHorario) {
                val faixa = LinearLayout(contexto).apply { orientation = LinearLayout.VERTICAL }
                faixa.addView(horario)
                faixa.addView(aoVivo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                raiz.addView(faixa, LinearLayout.LayoutParams(dp(88), -2).apply { marginEnd = dp(8) })
            }
            raiz.addView(titulo, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
}
