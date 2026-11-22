package br.com.lumeflix.app

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil3.BitmapImage
import coil3.load
import coil3.request.allowHardware

/** A arte fica sobre o lado escuro do banner. Mede apenas os pixels opacos da marca. */
internal fun ImageView.mostrarLogo(ficha: Detalhes.Ficha, titulo: TextView) {
    val urls = listOfNotNull(ficha.logo) + ficha.outrasLogos
    visibility = View.GONE
    titulo.visibility = View.VISIBLE
    contentDescription = titulo.text
    fun tentar(indice: Int) {
        val url = urls.getOrNull(indice) ?: return
        load(url) {
            size(500, 180)
            allowHardware(false)
            listener(onSuccess = { _, resultado ->
                val bmp = (resultado.image as? BitmapImage)?.bitmap
                var amostras = 0
                var claras = 0
                if (bmp != null) {
                    for (y in 0 until bmp.height step maxOf(1, bmp.height / 32)) {
                        for (x in 0 until bmp.width step maxOf(1, bmp.width / 96)) {
                            val pixel = bmp.getPixel(x, y)
                            if (Color.alpha(pixel) < 160) continue
                            amostras++
                            if (androidx.core.graphics.ColorUtils.calculateLuminance(pixel) >= 0.22) claras++
                        }
                    }
                }
                if (amostras > 0 && claras * 2 >= amostras) {
                    visibility = View.VISIBLE
                    titulo.visibility = View.GONE
                } else tentar(indice + 1)
            }, onError = { _, _ -> tentar(indice + 1) })
        }
    }
    tentar(0)
}
