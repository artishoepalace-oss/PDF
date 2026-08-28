package com.artishoepalace.pdfmaker

import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File

data class V2Doc(val file: File? = null, val uri: Uri? = null, val name: String, val size: Long, val modified: Long, val pages: Int = 0) {
    val key: String get() = file?.absolutePath ?: uri.toString()
}
enum class V2Sort { SIZE, NAME, MODIFIED }
enum class V2Filter { ALL, RECENT, FAVORITES }
data class V2Tool(val label: String, val icon: ImageVector, val tint: Color, val action: () -> Unit)
data class V2Palette(val bg: Color, val card: Color, val alt: Color, val text: Color, val muted: Color, val gold: Color, val darkGold: Color)
