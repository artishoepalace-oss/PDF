package com.artishoepalace.pdfmaker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as CColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PdfMakerApp(this) }
    }
}

data class LocalDoc(val file: File, val pages: Int = 0)
enum class SortField { SIZE, NAME, CREATED, MODIFIED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfMakerApp(context: Context) {
    var tab by remember { mutableStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var sortField by remember { mutableStateOf(SortField.CREATED) }
    var descending by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var darkMode by remember { mutableStateOf(false) }

    val bg = if (darkMode) CColor(0xFF15171D) else CColor(0xFFF6F7FD)
    val card = if (darkMode) CColor(0xFF22252C) else CColor.White
    val text = if (darkMode) CColor.White else CColor(0xFF161616)
    val muted = if (darkMode) CColor(0xFFADB4C0) else CColor(0xFF7C8796)
    val blue = CColor(0xFF526BFF)

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            runCatching { imagesToPdf(context, uris) }
                .onSuccess { toast(context, "PDF created") ; refresh++ }
                .onFailure { toast(context, it.message ?: "Could not create PDF") }
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { importFile(context, it) }
                .onSuccess { toast(context, "PDF imported"); refresh++ }
                .onFailure { toast(context, "Import failed") }
        }
    }
    val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size >= 2) {
            runCatching { mergePdfs(context, uris) }
                .onSuccess { toast(context, "PDFs merged"); refresh++ }
                .onFailure { toast(context, it.message ?: "Merge failed") }
        } else toast(context, "Select at least 2 PDFs")
    }
    val toJpgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { pdfToJpg(context, it) }
                .onSuccess { toast(context, "Pages exported as JPG"); refresh++ }
                .onFailure { toast(context, "Conversion failed") }
        }
    }
    val compressPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { compressPdf(context, it) }
                .onSuccess { toast(context, "Compressed PDF created"); refresh++ }
                .onFailure { toast(context, "Compression failed") }
        }
    }
    val docxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { docxToPdf(context, it) }
                .onSuccess { toast(context, "DOCX converted to PDF"); refresh++ }
                .onFailure { toast(context, "DOCX conversion failed") }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        bmp?.let {
            runCatching { bitmapToPdf(context, it, "scan") }
                .onSuccess { toast(context, "Scan saved as PDF"); refresh++ }
                .onFailure { toast(context, "Scan failed") }
        }
    }

    MaterialTheme(colorScheme = if (darkMode) darkColorScheme(primary = blue) else lightColorScheme(primary = blue)) {
        Box(Modifier.fillMaxSize().background(bg)) {
            if (settings) {
                SettingsScreen(card, text, muted, darkMode, onDark = { darkMode = it }, onBack = { settings = false })
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (tab == 0) {
                        HomeScreen(
                            card, text, muted, blue,
                            onSettings = { settings = true },
                            onImagePdf = { imagePicker.launch("image/*") },
                            onScan = { camera.launch(null) },
                            onImport = { pdfPicker.launch(arrayOf("application/pdf")) },
                            onCompress = { compressPicker.launch(arrayOf("application/pdf")) },
                            onPdfJpg = { toJpgPicker.launch(arrayOf("application/pdf")) },
                            onMerge = { mergePicker.launch(arrayOf("application/pdf")) },
                            onDocx = { docxPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) },
                            onMore = { tab = 1 }
                        )
                        RecentSection(context, refresh, card, text, muted, onRefresh = { refresh++ })
                    } else {
                        FilesScreen(context, refresh, card, text, muted, blue, sortField, descending, selectMode, selected,
                            onSettings = { settings = true },
                            onSort = { sortOpen = true },
                            onToggleSelect = { selectMode = !selectMode; if (!selectMode) selected = emptySet() },
                            onSelected = { path -> selected = if (path in selected) selected - path else selected + path },
                            onRefresh = { refresh++ })
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationBar(containerColor = card) {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                        NavigationBarItem(selected = false, onClick = { imagePicker.launch("image/*") }, icon = {
                            Surface(shape = CircleShape, color = blue, shadowElevation = 12.dp, modifier = Modifier.size(64.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = CColor.White, modifier = Modifier.size(38.dp)) }
                            }
                        })
                        NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Description, null) }, label = { Text("Files") })
                    }
                }
            }

            if (sortOpen) {
                ModalBottomSheet(onDismissRequest = { sortOpen = false }, containerColor = card) {
                    Column(Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
                        Text("Sort By", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = text)
                        Spacer(Modifier.height(18.dp))
                        SortRow("File Size", Icons.Default.Storage, sortField == SortField.SIZE, text) { sortField = SortField.SIZE }
                        SortRow("Name", Icons.Default.InsertDriveFile, sortField == SortField.NAME, text) { sortField = SortField.NAME }
                        SortRow("Created Date", Icons.Default.CalendarMonth, sortField == SortField.CREATED, text) { sortField = SortField.CREATED }
                        SortRow("Modified Date", Icons.Default.EditCalendar, sortField == SortField.MODIFIED, text) { sortField = SortField.MODIFIED }
                        HorizontalDivider()
                        SortRow("Ascending", Icons.Default.ArrowUpward, !descending, text) { descending = false }
                        SortRow("Descending", Icons.Default.ArrowDownward, descending, text) { descending = true }
                        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            OutlinedButton(onClick = { sortOpen = false }, modifier = Modifier.weight(1f).height(56.dp)) { Text("CANCEL") }
                            Button(onClick = { sortOpen = false }, modifier = Modifier.weight(1f).height(56.dp)) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(card: CColor, text: CColor, muted: CColor, blue: CColor, onSettings:()->Unit, onImagePdf:()->Unit, onScan:()->Unit, onImport:()->Unit, onCompress:()->Unit, onPdfJpg:()->Unit, onMerge:()->Unit, onDocx:()->Unit, onMore:()->Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Home", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = text, modifier = Modifier.weight(1f))
            IconButton(onClick = {}) { Icon(Icons.Default.Search, null, tint = text, modifier = Modifier.size(34.dp)) }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, null, tint = text, modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(20.dp))
        val tools = listOf(
            Triple("Image to PDF", Icons.Default.PictureAsPdf, onImagePdf),
            Triple("Smart Scan", Icons.Default.DocumentScanner, onScan),
            Triple("Import PDF", Icons.Default.Folder, onImport),
            Triple("Compress", Icons.Default.Compress, onCompress),
            Triple("PDF to JPG", Icons.Default.Image, onPdfJpg),
            Triple("Merge PDF", Icons.Default.CallMerge, onMerge),
            Triple("Docx to PDF", Icons.Default.Description, onDocx),
            Triple("More", Icons.Default.Dashboard, onMore)
        )
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for (row in tools.chunked(4)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEachIndexed { index, item -> ToolButton(item.first, item.second, card, text, blue, item.third, index) }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(label:String, icon:ImageVector, card:CColor, text:CColor, blue:CColor, onClick:()->Unit, index:Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(82.dp).clickable { onClick() }) {
        Surface(shape = CircleShape, color = card, modifier = Modifier.size(72.dp), shadowElevation = 1.dp) {
            Box(contentAlignment = Alignment.Center) {
                val tint = when(index) { 0 -> CColor(0xFFFF4D6D); 1 -> CColor(0xFF4E82FF); 2 -> CColor(0xFFFFB02E); 3 -> CColor(0xFFFF4D6D); else -> blue }
                Icon(icon, null, tint = tint, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun RecentSection(context: Context, refresh:Int, card:CColor, text:CColor, muted:CColor, onRefresh:()->Unit) {
    val docs = remember(refresh) { listDocs(context).take(3) }
    Surface(color = card, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("All (${docs.size})", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = text)
            Spacer(Modifier.height(12.dp))
            if (docs.isEmpty()) Text("No files yet. Tap + or Image to PDF.", color = muted)
            docs.forEach { FileRow(context, it, card, text, muted, false, false, {}, onRefresh) }
        }
    }
}

@Composable
private fun FilesScreen(context:Context, refresh:Int, card:CColor, text:CColor, muted:CColor, blue:CColor, sortField:SortField, descending:Boolean, selectMode:Boolean, selected:Set<String>, onSettings:()->Unit, onSort:()->Unit, onToggleSelect:()->Unit, onSelected:(String)->Unit, onRefresh:()->Unit) {
    val docs = remember(refresh, sortField, descending) {
        val all = listDocs(context)
        val sorted = when(sortField) {
            SortField.SIZE -> all.sortedBy { it.file.length() }
            SortField.NAME -> all.sortedBy { it.file.name.lowercase() }
            SortField.CREATED -> all.sortedBy { it.file.lastModified() }
            SortField.MODIFIED -> all.sortedBy { it.file.lastModified() }
        }
        if (descending) sorted.reversed() else sorted
    }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Files", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = text, modifier = Modifier.weight(1f))
            IconButton(onClick = {}) { Icon(Icons.Default.Search, null, tint = text, modifier = Modifier.size(32.dp)) }
            IconButton(onClick = onToggleSelect) { Icon(Icons.Default.SelectAll, null, tint = text, modifier = Modifier.size(30.dp)) }
            IconButton(onClick = onSort) { Icon(Icons.Default.Sort, null, tint = text, modifier = Modifier.size(32.dp)) }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, null, tint = text, modifier = Modifier.size(30.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 18.dp)) {
            FilterChip(selected = true, onClick = {}, label = { Text("All Files") })
            FilterChip(selected = false, onClick = {}, label = { Text("Recent") })
            FilterChip(selected = false, onClick = {}, label = { Text("Favorites") })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSort) { Icon(Icons.Default.FilterList, null, tint = text) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(docs, key = { it.file.absolutePath }) { doc ->
                FileRow(context, doc, card, text, muted, selectMode, doc.file.absolutePath in selected, { onSelected(doc.file.absolutePath) }, onRefresh)
            }
        }
    }
}

@Composable
private fun FileRow(context:Context, doc:LocalDoc, card:CColor, text:CColor, muted:CColor, selectMode:Boolean, checked:Boolean, onCheck:()->Unit, onRefresh:()->Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = card, modifier = Modifier.fillMaxWidth().height(126.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = CColor(0xFFF0F2F9), modifier = Modifier.size(92.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(if (doc.file.extension.equals("pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.Image, null, tint = if (doc.file.extension.equals("pdf", true)) CColor(0xFFFF365F) else CColor(0xFF1EB8A5), modifier = Modifier.size(46.dp)) }
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(doc.file.nameWithoutExtension, color = text, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text("${formatDate(doc.file.lastModified())}    ${formatSize(doc.file.length())}", color = muted, fontSize = 14.sp)
                if (doc.pages > 0) Text("${doc.pages} pages", color = muted, fontSize = 12.sp)
            }
            if (selectMode) {
                Checkbox(checked = checked, onCheckedChange = { onCheck() })
            } else {
                Column {
                    IconButton(onClick = { shareFile(context, doc.file) }) { Icon(Icons.Default.Share, null, tint = muted) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = muted) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; shareFile(context, doc.file) })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; doc.file.delete(); onRefresh() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(card:CColor, text:CColor, muted:CColor, dark:Boolean, onDark:(Boolean)->Unit, onBack:()->Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = text) }
            Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = text)
        }
        Spacer(Modifier.height(20.dp))
        Surface(shape = RoundedCornerShape(22.dp), color = card) {
            Column {
                SettingRow("Share App", Icons.Default.Share, text, muted)
                SettingRow("Remove Ads", Icons.Default.Block, text, muted)
                SettingRow("Scan Settings", Icons.Default.CenterFocusStrong, text, muted)
                SettingSwitch("Security Question", Icons.Default.Security, false, text, muted) {}
                SettingRow("Language Options", Icons.Default.Language, text, muted, "Default")
                SettingSwitch("Dark Mode", Icons.Default.DarkMode, dark, text, muted, onDark)
            }
        }
        Spacer(Modifier.height(18.dp))
        Surface(shape = RoundedCornerShape(22.dp), color = card) {
            Column {
                SettingRow("Feedback", Icons.Default.Feedback, text, muted)
                SettingRow("Request a new feature", Icons.Default.Send, text, muted)
                SettingRow("Privacy Policy", Icons.Default.PrivacyTip, text, muted)
                SettingRow("Version", Icons.Default.Info, text, muted, "1.0.0")
            }
        }
    }
}

@Composable private fun SettingRow(title:String, icon:ImageVector, text:CColor, muted:CColor, trailing:String="") {
    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = muted); Spacer(Modifier.width(16.dp)); Text(title, color = text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f)); if (trailing.isNotEmpty()) Text(trailing, color = if (trailing == "Default") CColor(0xFF526BFF) else muted)
    }
}
@Composable private fun SettingSwitch(title:String, icon:ImageVector, checked:Boolean, text:CColor, muted:CColor, onChecked:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = muted); Spacer(Modifier.width(16.dp)); Text(title, color = text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChecked)
    }
}
@Composable private fun SortRow(label:String, icon:ImageVector, selected:Boolean, text:CColor, onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().height(62.dp).clickable { onClick() }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = CColor(0xFF7C8796)); Spacer(Modifier.width(18.dp)); Text(label, color = text, fontSize = 20.sp, modifier = Modifier.weight(1f)); RadioButton(selected = selected, onClick = onClick)
    }
}

private fun appDir(context:Context):File = File(context.getExternalFilesDir(null), "Documents").apply { mkdirs() }
private fun listDocs(context:Context):List<LocalDoc> = appDir(context).listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("pdf","jpg","jpeg","png") }?.map { LocalDoc(it, if (it.extension.equals("pdf",true)) countPages(it) else 0) } ?: emptyList()
private fun output(context:Context, prefix:String, ext:String="pdf") = File(appDir(context), "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$ext")

private fun imagesToPdf(context:Context, uris:List<Uri>):File {
    val pdf = PdfDocument(); val out = output(context, "Image_to_PDF")
    uris.forEachIndexed { index, uri ->
        context.contentResolver.openInputStream(uri).use { input ->
            val bmp = BitmapFactory.decodeStream(input) ?: return@use
            val width = 1240; val height = 1754
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
            drawBitmapFit(page.canvas, bmp, width, height)
            pdf.finishPage(page); bmp.recycle()
        }
    }
    FileOutputStream(out).use { pdf.writeTo(it) }; pdf.close(); return out
}
private fun bitmapToPdf(context:Context, bmp:Bitmap, prefix:String):File {
    val pdf = PdfDocument(); val out = output(context, prefix)
    val page = pdf.startPage(PdfDocument.PageInfo.Builder(1240,1754,1).create()); drawBitmapFit(page.canvas,bmp,1240,1754); pdf.finishPage(page)
    FileOutputStream(out).use { pdf.writeTo(it) }; pdf.close(); return out
}
private fun drawBitmapFit(canvas:Canvas, bmp:Bitmap, w:Int, h:Int) {
    canvas.drawColor(Color.WHITE)
    val scale = minOf(w.toFloat()/bmp.width, h.toFloat()/bmp.height)
    val dw = bmp.width*scale; val dh=bmp.height*scale
    val left=(w-dw)/2f; val top=(h-dh)/2f
    canvas.drawBitmap(bmp, null, android.graphics.RectF(left,top,left+dw,top+dh), Paint(Paint.ANTI_ALIAS_FLAG))
}
private fun importFile(context:Context, uri:Uri):File {
    val name = queryName(context, uri) ?: "imported_${System.currentTimeMillis()}.pdf"
    val out = File(appDir(context), if(name.endsWith(".pdf",true)) name else "$name.pdf")
    context.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(out).use { input.copyTo(it) } }; return out
}
private fun mergePdfs(context:Context, uris:List<Uri>):File {
    val outDoc = PdfDocument(); var index=1
    uris.forEach { uri ->
        context.contentResolver.openFileDescriptor(uri,"r")!!.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                for(i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { p ->
                        val bmp=Bitmap.createBitmap(1240,1754,Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.WHITE); p.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val page=outDoc.startPage(PdfDocument.PageInfo.Builder(1240,1754,index++).create()); drawBitmapFit(page.canvas,bmp,1240,1754); outDoc.finishPage(page); bmp.recycle()
                    }
                }
            }
        }
    }
    val out=output(context,"Merged_PDF"); FileOutputStream(out).use{outDoc.writeTo(it)}; outDoc.close(); return out
}
private fun compressPdf(context:Context, uri:Uri):File {
    val outDoc=PdfDocument(); var index=1
    context.contentResolver.openFileDescriptor(uri,"r")!!.use { pfd -> PdfRenderer(pfd).use { renderer ->
        for(i in 0 until renderer.pageCount){ renderer.openPage(i).use { p ->
            val bmp=Bitmap.createBitmap(900,1273,Bitmap.Config.RGB_565); p.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val page=outDoc.startPage(PdfDocument.PageInfo.Builder(900,1273,index++).create()); drawBitmapFit(page.canvas,bmp,900,1273); outDoc.finishPage(page); bmp.recycle()
        }}
    }}
    val out=output(context,"Compressed"); FileOutputStream(out).use{outDoc.writeTo(it)}; outDoc.close(); return out
}
private fun pdfToJpg(context:Context, uri:Uri):List<File> {
    val files=mutableListOf<File>()
    context.contentResolver.openFileDescriptor(uri,"r")!!.use { pfd -> PdfRenderer(pfd).use { renderer ->
        for(i in 0 until renderer.pageCount){ renderer.openPage(i).use { p ->
            val bmp=Bitmap.createBitmap(1240,1754,Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.WHITE); p.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val out=output(context,"PDF_page_${i+1}","jpg"); FileOutputStream(out).use{bmp.compress(Bitmap.CompressFormat.JPEG,92,it)}; bmp.recycle(); files+=out
        }}
    }}; return files
}
private fun docxToPdf(context:Context, uri:Uri):File {
    var xml=""
    context.contentResolver.openInputStream(uri)!!.use { input -> ZipInputStream(input).use { zip ->
        var e=zip.nextEntry
        while(e!=null){ if(e.name=="word/document.xml") { xml=zip.bufferedReader().readText(); break }; e=zip.nextEntry }
    }}
    val plain=xml.replace(Regex("<w:tab[^>]*/>"),"\t").replace(Regex("</w:p>"),"\n").replace(Regex("<[^>]+>"),"").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")
    val lines=plain.lines().flatMap { line -> if(line.length<=75) listOf(line) else line.chunked(75) }
    val pdf=PdfDocument(); val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=28f}; val perPage=52
    lines.chunked(perPage).forEachIndexed { pi, chunk -> val page=pdf.startPage(PdfDocument.PageInfo.Builder(1240,1754,pi+1).create()); page.canvas.drawColor(Color.WHITE); var y=70f; chunk.forEach { page.canvas.drawText(it,70f,y,paint); y+=31f }; pdf.finishPage(page) }
    if(lines.isEmpty()){ val page=pdf.startPage(PdfDocument.PageInfo.Builder(1240,1754,1).create()); page.canvas.drawColor(Color.WHITE); pdf.finishPage(page) }
    val out=output(context,"DOCX_to_PDF"); FileOutputStream(out).use{pdf.writeTo(it)}; pdf.close(); return out
}
private fun countPages(file:File):Int = runCatching { ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } } }.getOrDefault(0)
private fun queryName(context:Context, uri:Uri):String? { context.contentResolver.query(uri,null,null,null,null)?.use { c -> val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(c.moveToFirst() && i>=0) return c.getString(i) }; return null }
private fun formatDate(ms:Long)=SimpleDateFormat("MM/dd HH:mm",Locale.US).format(Date(ms))
private fun formatSize(bytes:Long):String = when { bytes>=1024*1024 -> String.format(Locale.US,"%.1f MB",bytes/1048576.0); bytes>=1024 -> String.format(Locale.US,"%.1f kB",bytes/1024.0); else -> "$bytes B" }
private fun shareFile(context:Context,file:File){ val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file); val type=if(file.extension.equals("pdf",true))"application/pdf" else "image/*"; context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{this.type=type;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share")) }
private fun toast(context:Context,msg:String)=Toast.makeText(context,msg,Toast.LENGTH_SHORT).show()
