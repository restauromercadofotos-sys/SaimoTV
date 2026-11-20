package br.com.sintonia.tv

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import androidx.media3.common.util.UnstableApi

/** Sugestões de canais para a busca do Android TV. */
@UnstableApi
class BuscaDoSistema : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val colunas = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
        )
        val cursor = MatrixCursor(colunas)
        val termo = (selectionArgs?.firstOrNull() ?: uri.lastPathSegment).orEmpty()
            .takeUnless { it == SearchManager.SUGGEST_URI_PATH_QUERY }.orEmpty().trim()
        if (termo.length < 2) return cursor
        val contexto = context ?: return cursor
        if (Remote.channels.isEmpty() && Enderecos.canais != null) Remote.loadCached(contexto)
        val limite = uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT)?.toIntOrNull()?.coerceIn(0, 40) ?: 20
        buscarCanais(termo).take(limite).forEachIndexed { i, canal ->
            cursor.addRow(arrayOf<Any?>(i, canal.name, Categorias.de(canal), canal.logo, "video/*",
                android.content.Intent.ACTION_VIEW, Links.canal(canal.name).toString()))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
