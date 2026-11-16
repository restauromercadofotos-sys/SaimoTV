package br.com.saimo.tv

/** Limites de navegação das duas variantes; o player e os controles são compartilhados. */
object Conteudo {
    fun permiteAba(aba: Aba): Boolean = when (aba) {
        Aba.AO_VIVO, Aba.EVENTOS -> BuildConfig.TV_AO_VIVO
        Aba.ON_DEMAND, Aba.SERIES, Aba.FAVORITOS, Aba.EXTRAS, Aba.BUSCAR -> !BuildConfig.TV_AO_VIVO
        Aba.RADIOS, Aba.APOIE -> false
        else -> true
    }

    fun permiteLink(host: String?): Boolean = when (host) {
        "canal" -> BuildConfig.TV_AO_VIVO
        "assistir", "titulo" -> !BuildConfig.TV_AO_VIVO
        "inicio" -> true
        else -> false
    }

    fun permiteComando(tipo: String): Boolean = when (tipo) {
        "tune", "multi", "activeScreen", "tvAudio" -> BuildConfig.TV_AO_VIVO
        "playVod", "playMedia" -> !BuildConfig.TV_AO_VIVO
        else -> true
    }
}
