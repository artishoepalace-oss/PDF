package com.artishoepalace.pdfmaker

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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

data class LocalDoc(
    val file: File? = null,
    val uri: Uri? = null,
    val name: String,
    val size: Long,
    val modified: Long,
    val pages: Int = 0
) {
    val key: String get() = file?.absolutePath ?: uri.toString()
}

enum class SortField { SIZE, NAME, CREATED, MODIFIED }
enum class FileFilter { ALL, RECENT, FAVORITES }

data class ToolItem(val label:String, val icon:ImageVector, val action:()->Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfMakerApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("pdf_maker", Context.MODE_PRIVATE) }
    var tab by remember { mutableIntStateOf(0) }
    var showTools by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var sortField by remember { mutableStateOf(SortField.MODIFIED) }
    var descending by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
    var search by remember { mutableStateOf("") }
    var aiResult by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }

    val bg = if (darkMode) CColor(0xFF15171D) else CColor(0xFFF7F8FD)
    val card = if (darkMode) CColor(0xFF23262D) else CColor.White
    val text = if (darkMode) CColor.White else CColor(0xFF151515)
    val muted = if (darkMode) CColor(0xFFADB4C0) else CColor(0xFF7B8797)
    val blue = CColor(0xFF536DFF)

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) refresh++ else toast(context, "Storage permission is needed to scan device PDFs")
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) runCatching { imagesToPdf(context, uris) }
            .onSuccess { toast(context, "PDF created"); refresh++ }
            .onFailure { toast(context, it.message ?: "Could not create PDF") }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { importFile(context, it) }.onSuccess { toast(context, "PDF imported"); refresh++ }.onFailure { toast(context, "Import failed") } }
    }
    val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size < 2) toast(context, "Select at least 2 PDFs")
        else runCatching { mergePdfs(context, uris) }.onSuccess { toast(context, "PDFs merged"); refresh++ }.onFailure { toast(context, it.message ?: "Merge failed") }
    }
    val convertPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { pdfToImages(context, it, "jpg") }.onSuccess { toast(context, "Pages exported as JPG"); refresh++ }.onFailure { toast(context, "Conversion failed") } }
    }
    val pngPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { pdfToImages(context, it, "png") }.onSuccess { toast(context, "Pages exported as PNG"); refresh++ }.onFailure { toast(context, "Conversion failed") } }
    }
    val compressPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { compressPdf(context, it) }.onSuccess { toast(context, "Compressed PDF created"); refresh++ }.onFailure { toast(context, "Compression failed") } }
    }
    val docxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { runCatching { docxToPdf(context, it) }.onSuccess { toast(context, "DOCX converted to PDF"); refresh++ }.onFailure { toast(context, "DOCX conversion failed") } }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        bmp?.let { runCatching { bitmapToPdf(context, it, "scan") }.onSuccess { toast(context, "Scan saved as PDF"); refresh++ }.onFailure { toast(context, "Scan failed") } }
    }
    val idCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        bmp?.let { runCatching { bitmapToPdf(context, it, "id_scan") }.onSuccess { toast(context, "ID scan saved"); refresh++ } }
    }
    val aiCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp == null) return@rememberLauncherForActivityResult
        val key = prefs.getString("openai_key", "") ?: ""
        val model = prefs.getString("openai_model", "gpt-5.6-luna") ?: "gpt-5.6-luna"
        if (key.isBlank()) { toast(context, "Add your OpenAI API key in Settings > AI Scan"); showSettings = true }
        else {
            aiBusy = true
            Thread {
                val result = runCatching { aiReadImage(bmp, key, model) }.getOrElse { "AI Scan failed: ${it.message}" }
                (context as? MainActivity)?.runOnUiThread { aiBusy = false; aiResult = result }
            }.start()
        }
    }

    val featureUnavailable: (String)->Unit = { name ->
        toast(context, "$name needs an Office/PDF conversion engine; this build does not fake conversion.")
    }

    MaterialTheme(colorScheme = if (darkMode) darkColorScheme(primary = blue) else lightColorScheme(primary = blue)) {
        Scaffold(
            containerColor = bg,
            bottomBar = {
                if (!showSettings && !showTools) {
                    NavigationBar(containerColor = card, tonalElevation = 4.dp, modifier = Modifier.height(76.dp)) {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home", fontSize = 11.sp) }
                        )
                        NavigationBarItem(
                            selected = false, onClick = { imagePicker.launch("image/*") },
                            icon = {
                                Surface(shape = CircleShape, color = blue, shadowElevation = 8.dp, modifier = Modifier.size(54.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = CColor.White, modifier = Modifier.size(30.dp)) }
                                }
                            }
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.Default.Description, null) }, label = { Text("Files", fontSize = 11.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(bg)) {
                when {
                    showSettings -> SettingsScreen(
                        context, card, text, muted, darkMode,
                        onDark = { darkMode = it; prefs.edit().putBoolean("dark", it).apply() },
                        onBack = { showSettings = false },
                        onRescan = {
                            if (Build.VERSION.SDK_INT <= 32 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                                storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            else { refresh++; toast(context, "Device PDF scan refreshed") }
                        }
                    )
                    showTools -> ToolsScreen(
                        card, text, muted,
                        onBack = { showTools = false },
                        convert = listOf(
                            ToolItem("Image to PDF", Icons.Default.PictureAsPdf) { imagePicker.launch("image/*") },
                            ToolItem("Docx to PDF", Icons.Default.Description) { docxPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) },
                            ToolItem("PPT to PDF", Icons.Default.Slideshow) { featureUnavailable("PPT to PDF") },
                            ToolItem("PDF to JPG", Icons.Default.Image) { convertPicker.launch(arrayOf("application/pdf")) },
                            ToolItem("PDF to PNG", Icons.Default.Photo) { pngPicker.launch(arrayOf("application/pdf")) },
                            ToolItem("PDF to Word", Icons.Default.Article) { featureUnavailable("PDF to Word") },
                            ToolItem("PDF to PPT", Icons.Default.Slideshow) { featureUnavailable("PDF to PPT") },
                            ToolItem("Excel to PDF", Icons.Default.TableChart) { featureUnavailable("Excel to PDF") }
                        ),
                        popular = listOf(
                            ToolItem("Smart Scan", Icons.Default.DocumentScanner) { camera.launch(null) },
                            ToolItem("Scan ID Card", Icons.Default.Badge) { idCamera.launch(null) },
                            ToolItem("AI Scan", Icons.Default.AutoAwesome) { aiCamera.launch(null) },
                            ToolItem("Import PDF", Icons.Default.Folder) { pdfPicker.launch(arrayOf("application/pdf")) },
                            ToolItem("Print PDF", Icons.Default.Print) { toast(context, "Open a PDF from Files and choose Print/Share") },
                            ToolItem("Rescan Files", Icons.Default.Refresh) { refresh++; toast(context, "File list refreshed") }
                        ),
                        edit = listOf(
                            ToolItem("Merge PDF", Icons.Default.CallMerge) { mergePicker.launch(arrayOf("application/pdf")) },
                            ToolItem("Compress", Icons.Default.Compress) { compressPicker.launch(arrayOf("application/pdf")) },
                            ToolItem("Doodle", Icons.Default.Draw) { featureUnavailable("Doodle") },
                            ToolItem("Add Text", Icons.Default.TextFields) { featureUnavailable("Add Text") },
                            ToolItem("Signature", Icons.Default.Draw) { featureUnavailable("Signature") },
                            ToolItem("Lock PDF", Icons.Default.Lock) { featureUnavailable("Lock PDF") },
                            ToolItem("Unlock PDF", Icons.Default.LockOpen) { featureUnavailable("Unlock PDF") }
                        )
                    )
                    tab == 0 -> HomeScreen(
                        context, refresh, card, text, muted, blue,
                        onSettings = { showSettings = true },
                        onSearch = { search = it },
                        onImagePdf = { imagePicker.launch("image/*") },
                        onScan = { camera.launch(null) },
                        onImport = { pdfPicker.launch(arrayOf("application/pdf")) },
                        onCompress = { compressPicker.launch(arrayOf("application/pdf")) },
                        onPdfJpg = { convertPicker.launch(arrayOf("application/pdf")) },
                        onMerge = { mergePicker.launch(arrayOf("application/pdf")) },
                        onDocx = { docxPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) },
                        onMore = { showTools = true },
                        onRefresh = { refresh++ }
                    )
                    else -> FilesScreen(
                        context, refresh, card, text, muted, blue, sortField, descending, search,
                        onSearch = { search = it }, onSettings = { showSettings = true }, onSort = { sortOpen = true }, onRefresh = { refresh++ }
                    )
                }

                if (aiBusy) {
                    Surface(color = CColor.Black.copy(alpha=.45f), modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) {
                            Card { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(); Spacer(Modifier.width(16.dp)); Text("AI is reading the scan…")
                            } }
                        }
                    }
                }
            }
        }

        if (sortOpen) ModalBottomSheet(onDismissRequest = { sortOpen = false }, containerColor = card) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("Sort By", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = text)
                SortRow("File Size", Icons.Default.Storage, sortField == SortField.SIZE, text) { sortField = SortField.SIZE }
                SortRow("Name", Icons.Default.InsertDriveFile, sortField == SortField.NAME, text) { sortField = SortField.NAME }
                SortRow("Created Date", Icons.Default.CalendarMonth, sortField == SortField.CREATED, text) { sortField = SortField.CREATED }
                SortRow("Modified Date", Icons.Default.EditCalendar, sortField == SortField.MODIFIED, text) { sortField = SortField.MODIFIED }
                HorizontalDivider()
                SortRow("Ascending", Icons.Default.ArrowUpward, !descending, text) { descending = false }
                SortRow("Descending", Icons.Default.ArrowDownward, descending, text) { descending = true }
                Button(onClick = { sortOpen = false }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(52.dp)) { Text("APPLY") }
            }
        }

        aiResult?.let { result ->
            AlertDialog(
                onDismissRequest = { aiResult = null },
                title = { Text("AI Scan Result") },
                text = { LazyColumn(Modifier.heightIn(max = 420.dp)) { item { Text(result) } } },
                confirmButton = { TextButton(onClick = { aiResult = null }) { Text("DONE") } }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    context: Context, refresh:Int, card:CColor, text:CColor, muted:CColor, blue:CColor,
    onSettings:()->Unit, onSearch:(String)->Unit, onImagePdf:()->Unit, onScan:()->Unit,
    onImport:()->Unit, onCompress:()->Unit, onPdfJpg:()->Unit, onMerge:()->Unit,
    onDocx:()->Unit, onMore:()->Unit, onRefresh:()->Unit
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val docs = remember(refresh, query) { listDocs(context).filter { query.isBlank() || it.name.contains(query, true) }.take(4) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it; onSearch(it) },
                            singleLine = true, placeholder = { Text("Search PDFs") },
                            modifier = Modifier.weight(1f).height(52.dp)
                        )
                        IconButton(onClick = { searching = false; query = ""; onSearch("") }) { Icon(Icons.Default.Close, null) }
                    } else {
                        Text("Home", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = text, modifier = Modifier.weight(1f))
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, null, tint = text, modifier = Modifier.size(28.dp)) }
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, null, tint = text, modifier = Modifier.size(27.dp)) }
                    }
                }
                Spacer(Modifier.height(18.dp))
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
                tools.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEachIndexed { index, item -> ToolButton(item.first, item.second, card, text, blue, item.third, index) }
                    }
                }
            }
        }
        item {
            Surface(color = card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("All (${docs.size})", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = text, modifier = Modifier.weight(1f))
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null, tint = muted) }
                    }
                    if (docs.isEmpty()) Text("No PDFs found. Grant storage permission or create/import a PDF.", color = muted, modifier = Modifier.padding(vertical = 20.dp))
                    docs.forEach { FileRow(context, it, card, text, muted, onRefresh) }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(label:String, icon:ImageVector, card:CColor, text:CColor, blue:CColor, onClick:()->Unit, index:Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(82.dp).clickable { onClick() }) {
        Surface(shape = CircleShape, color = card, modifier = Modifier.size(68.dp), shadowElevation = 1.dp) {
            Box(contentAlignment = Alignment.Center) {
                val tint = when(index % 4) { 0 -> CColor(0xFFFF4D6D); 1 -> CColor(0xFF4E82FF); 2 -> CColor(0xFFFFA928); else -> CColor(0xFFFF4D6D) }
                Icon(icon, null, tint = tint, modifier = Modifier.size(31.dp))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(label, color = text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolsScreen(
    card:CColor, text:CColor, muted:CColor, onBack:()->Unit,
    convert:List<ToolItem>, popular:List<ToolItem>, edit:List<ToolItem>
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = text) }
                Text("Tools", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = text)
            }
            Surface(shape = RoundedCornerShape(22.dp), color = card, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TipsAndUpdates, null, tint = CColor(0xFF1E88E5), modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(16.dp))
                    Column { Text("Request a new feature", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = text); Text("More PDF tools are added here", color = muted, fontSize = 13.sp) }
                }
            }
        }
        item { ToolSection("Convert", convert, card, text) }
        item { ToolSection("Popular", popular, card, text) }
        item { ToolSection("Edit", edit, card, text) }
    }
}

@Composable
private fun ToolSection(title:String, tools:List<ToolItem>, card:CColor, text:CColor) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = text, modifier = Modifier.padding(vertical = 8.dp))
        tools.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tool ->
                    Surface(shape = RoundedCornerShape(20.dp), color = card, modifier = Modifier.weight(1f).height(126.dp).clickable { tool.action() }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(tool.icon, null, tint = CColor(0xFF4C80EA), modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(tool.label, color = text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                        }
                    }
                }
                repeat(3-row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FilesScreen(
    context:Context, refresh:Int, card:CColor, text:CColor, muted:CColor, blue:CColor,
    sortField:SortField, descending:Boolean, globalSearch:String,
    onSearch:(String)->Unit, onSettings:()->Unit, onSort:()->Unit, onRefresh:()->Unit
) {
    val prefs = remember { context.getSharedPreferences("pdf_maker", Context.MODE_PRIVATE) }
    var filter by remember { mutableStateOf(FileFilter.ALL) }
    var searching by remember { mutableStateOf(false) }
    var query by remember(globalSearch) { mutableStateOf(globalSearch) }
    var localRefresh by remember { mutableIntStateOf(0) }

    val favorites = remember(refresh, localRefresh) { prefs.getStringSet("favorites", emptySet()) ?: emptySet() }
    val docs = remember(refresh, localRefresh, sortField, descending, filter, query) {
        var all = listDocs(context)
        if (query.isNotBlank()) all = all.filter { it.name.contains(query, true) }
        all = when(filter) {
            FileFilter.ALL -> all
            FileFilter.RECENT -> all.filter { System.currentTimeMillis() - it.modified <= 7L*24*60*60*1000 }
            FileFilter.FAVORITES -> all.filter { it.key in favorites }
        }
        val sorted = when(sortField) {
            SortField.SIZE -> all.sortedBy { it.size }
            SortField.NAME -> all.sortedBy { it.name.lowercase() }
            SortField.CREATED, SortField.MODIFIED -> all.sortedBy { it.modified }
        }
        if (descending) sorted.reversed() else sorted
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                OutlinedTextField(value = query, onValueChange = { query = it; onSearch(it) }, singleLine = true, modifier = Modifier.weight(1f).height(52.dp), placeholder = { Text("Search files") })
                IconButton(onClick = { searching = false; query = ""; onSearch("") }) { Icon(Icons.Default.Close, null) }
            } else {
                Text("Files", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = text, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, null, tint = text) }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null, tint = text) }
                IconButton(onClick = onSort) { Icon(Icons.Default.Sort, null, tint = text) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, null, tint = text) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
            FilterChip(selected = filter == FileFilter.ALL, onClick = { filter = FileFilter.ALL }, label = { Text("All Files", fontSize = 12.sp) })
            FilterChip(selected = filter == FileFilter.RECENT, onClick = { filter = FileFilter.RECENT }, label = { Text("Recent", fontSize = 12.sp) })
            FilterChip(selected = filter == FileFilter.FAVORITES, onClick = { filter = FileFilter.FAVORITES }, label = { Text("Favorites", fontSize = 12.sp) })
        }
        Text("${docs.size} PDF files", color = muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (docs.isEmpty()) item { Text(if(filter==FileFilter.FAVORITES) "No favorite files yet. Tap ☆ on a file." else "No PDFs found on this device.", color = muted, modifier = Modifier.padding(20.dp)) }
            items(docs, key = { it.key }) { doc ->
                FileRow(context, doc, card, text, muted) { localRefresh++; onRefresh() }
            }
        }
    }
}

@Composable
private fun FileRow(context:Context, doc:LocalDoc, card:CColor, text:CColor, muted:CColor, onRefresh:()->Unit) {
    val prefs = remember { context.getSharedPreferences("pdf_maker", Context.MODE_PRIVATE) }
    var menu by remember { mutableStateOf(false) }
    var favorite by remember(doc.key) { mutableStateOf(doc.key in (prefs.getStringSet("favorites", emptySet()) ?: emptySet())) }

    Surface(shape = RoundedCornerShape(20.dp), color = card, modifier = Modifier.fillMaxWidth().height(112.dp).clickable { openDoc(context, doc) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(15.dp), color = CColor(0xFFF0F2F9), modifier = Modifier.size(78.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.PictureAsPdf, null, tint = CColor(0xFFFF365F), modifier = Modifier.size(40.dp)) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(doc.name.substringBeforeLast("."), color = text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text("${formatDate(doc.modified)}   ${formatSize(doc.size)}", color = muted, fontSize = 12.sp)
                if (doc.pages > 0) Text("${doc.pages} pages", color = muted, fontSize = 12.sp)
            }
            IconButton(onClick = {
                val set = (prefs.getStringSet("favorites", emptySet()) ?: emptySet()).toMutableSet()
                favorite = !favorite
                if (favorite) set += doc.key else set -= doc.key
                prefs.edit().putStringSet("favorites", set).apply(); onRefresh()
            }) { Icon(if(favorite) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if(favorite) CColor(0xFFFFB300) else muted) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = { menu=false; openDoc(context, doc) })
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = { menu=false; shareDoc(context, doc) })
                    if (doc.file != null && doc.file.absolutePath.startsWith(context.filesDir.absolutePath)) {
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu=false; if(doc.file.delete()) { toast(context, "Deleted"); onRefresh() } })
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    context:Context, card:CColor, text:CColor, muted:CColor, dark:Boolean,
    onDark:(Boolean)->Unit, onBack:()->Unit, onRescan:()->Unit
) {
    val prefs = remember { context.getSharedPreferences("pdf_maker", Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString("openai_key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("openai_model", "gpt-5.6-luna") ?: "gpt-5.6-luna") }
    var autoScan by remember { mutableStateOf(prefs.getBoolean("auto_scan", true)) }
    var security by remember { mutableStateOf(prefs.getBoolean("security", false)) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = text) }
                Text("Settings", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = text)
            }
        }
        item {
            SettingsCard(card) {
                SettingAction(Icons.Default.Share, "Share App", text) {
                    val i = Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT, "PDF Maker") }
                    context.startActivity(Intent.createChooser(i, "Share PDF Maker"))
                }
                SettingAction(Icons.Default.Refresh, "Scan device PDFs now", text, onRescan)
                ToggleSetting(Icons.Default.DocumentScanner, "Auto scan device PDFs", autoScan, text) { autoScan=it; prefs.edit().putBoolean("auto_scan", it).apply() }
                ToggleSetting(Icons.Default.Security, "Security", security, text) { security=it; prefs.edit().putBoolean("security", it).apply() }
                ToggleSetting(Icons.Default.DarkMode, "Dark Mode", dark, text, onDark)
            }
        }
        item {
            Text("AI Scan", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            SettingsCard(card) {
                Text("Use your own OpenAI API key to read photographed documents. The key is stored only in this app's local preferences.", color = muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = key, onValueChange = { key=it }, label={Text("OpenAI API key")}, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = model, onValueChange = { model=it }, label={Text("Model")}, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { prefs.edit().putString("openai_key", key.trim()).putString("openai_model", model.trim()).apply(); toast(context, "AI settings saved") }, modifier = Modifier.fillMaxWidth().padding(top=10.dp)) { Text("SAVE AI SETTINGS") }
            }
        }
        item {
            SettingsCard(card) {
                SettingAction(Icons.Default.Feedback, "Feedback", text) { toast(context, "Use the GitHub repository Issues page for feedback") }
                SettingAction(Icons.Default.Lightbulb, "Request a new feature", text) { toast(context, "Feature request noted") }
                SettingAction(Icons.Default.PrivacyTip, "Privacy Policy", text) { toast(context, "No cloud upload unless you use AI Scan") }
                Row(Modifier.fillMaxWidth().padding(vertical=14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint=muted); Spacer(Modifier.width(16.dp)); Text("Version", color=text, modifier=Modifier.weight(1f)); Text("1.1.0", color=muted)
                }
            }
        }
    }
}

@Composable private fun SettingsCard(card:CColor, content:@Composable ColumnScope.()->Unit) {
    Surface(shape=RoundedCornerShape(22.dp), color=card, modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp, vertical=6.dp)) {
        Column(Modifier.padding(18.dp), content=content)
    }
}
@Composable private fun SettingAction(icon:ImageVector, title:String, text:CColor, onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable{onClick()}.padding(vertical=14.dp), verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=CColor(0xFF748294)); Spacer(Modifier.width(16.dp)); Text(title,color=text,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f)); Icon(Icons.Default.ChevronRight,null,tint=CColor(0xFF9AA4B0))
    }
}
@Composable private fun ToggleSetting(icon:ImageVector, title:String, checked:Boolean, text:CColor, onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=10.dp), verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=CColor(0xFF748294)); Spacer(Modifier.width(16.dp)); Text(title,color=text,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f)); Switch(checked=checked,onCheckedChange=onChange)
    }
}
@Composable private fun SortRow(label:String, icon:ImageVector, selected:Boolean, text:CColor, onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable{onClick()}.padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=CColor(0xFF7B8797)); Spacer(Modifier.width(18.dp)); Text(label,color=text,fontSize=17.sp,modifier=Modifier.weight(1f)); RadioButton(selected=selected,onClick=onClick)
    }
}

private fun listDocs(context:Context):List<LocalDoc> {
    val map = linkedMapOf<String,LocalDoc>()
    val appFiles = context.filesDir.listFiles()?.filter { it.extension.equals("pdf",true) }.orEmpty()
    appFiles.forEach { f -> map[f.absolutePath] = LocalDoc(file=f,name=f.name,size=f.length(),modified=f.lastModified(),pages=pdfPages(f)) }

    runCatching {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = mutableListOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED)
        if (Build.VERSION.SDK_INT <= 28) projection += MediaStore.Files.FileColumns.DATA
        context.contentResolver.query(collection, projection.toTypedArray(), "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?", arrayOf("application/pdf","%.pdf"), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            val idI=c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameI=c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeI=c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modI=c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val dataI=if(Build.VERSION.SDK_INT<=28)c.getColumnIndex(MediaStore.Files.FileColumns.DATA) else -1
            while(c.moveToNext()) {
                val name=c.getString(nameI) ?: continue
                val size=c.getLong(sizeI)
                val mod=c.getLong(modI)*1000L
                val uri=ContentUris.withAppendedId(collection,c.getLong(idI))
                val path=if(dataI>=0)c.getString(dataI) else null
                val file=path?.let{File(it)}?.takeIf{it.exists()}
                val key=file?.absolutePath ?: uri.toString()
                map[key]=LocalDoc(file=file,uri=uri,name=name,size=size,modified=mod,pages=if(file!=null)pdfPages(file) else 0)
            }
        }
    }
    return map.values.sortedByDescending { it.modified }
}

private fun pdfPages(file:File):Int = runCatching {
    ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { p -> PdfRenderer(p).use { it.pageCount } }
}.getOrDefault(0)

private fun openDoc(context:Context, doc:LocalDoc) {
    val uri = doc.uri ?: doc.file?.let { FileProvider.getUriForFile(context,"${context.packageName}.files",it) } ?: return
    val i=Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri,"application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    runCatching { context.startActivity(i) }.onFailure { toast(context,"No PDF viewer installed") }
}
private fun shareDoc(context:Context, doc:LocalDoc) {
    val uri=doc.uri ?: doc.file?.let{FileProvider.getUriForFile(context,"${context.packageName}.files",it)} ?: return
    val i=Intent(Intent.ACTION_SEND).apply { type="application/pdf"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(Intent.createChooser(i,"Share PDF"))
}

private fun imagesToPdf(context:Context, uris:List<Uri>):File {
    val pdf=PdfDocument()
    uris.forEachIndexed { index,uri ->
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bmp=BitmapFactory.decodeStream(input) ?: return@use
            addBitmapPage(pdf,bmp,index+1)
            bmp.recycle()
        }
    }
    return savePdf(context,pdf,"Image_to_PDF")
}
private fun bitmapToPdf(context:Context,bmp:Bitmap,prefix:String):File {
    val pdf=PdfDocument(); addBitmapPage(pdf,bmp,1); return savePdf(context,pdf,prefix)
}
private fun addBitmapPage(pdf:PdfDocument,bmp:Bitmap,pageNo:Int) {
    val pw=595; val ph=842
    val page=pdf.startPage(PdfDocument.PageInfo.Builder(pw,ph,pageNo).create())
    val scale=minOf(pw.toFloat()/bmp.width, ph.toFloat()/bmp.height)
    val w=bmp.width*scale; val h=bmp.height*scale
    page.canvas.drawColor(Color.WHITE)
    page.canvas.drawBitmap(bmp,null,android.graphics.RectF((pw-w)/2,(ph-h)/2,(pw+w)/2,(ph+h)/2),Paint(Paint.ANTI_ALIAS_FLAG))
    pdf.finishPage(page)
}
private fun savePdf(context:Context,pdf:PdfDocument,prefix:String):File {
    val f=File(context.filesDir,"${prefix}_${stamp()}.pdf")
    FileOutputStream(f).use { pdf.writeTo(it) }; pdf.close(); return f
}
private fun importFile(context:Context,uri:Uri):File {
    val name=queryName(context,uri).ifBlank{"import_${stamp()}.pdf"}
    val target=uniqueFile(context.filesDir,name)
    context.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(target).use { input.copyTo(it) } }
    return target
}
private fun mergePdfs(context:Context,uris:List<Uri>):File {
    val out=PdfDocument(); var pageNo=1
    uris.forEach { uri ->
        context.contentResolver.openFileDescriptor(uri,"r")?.use { pfd ->
            PdfRenderer(pfd).use { r ->
                for(i in 0 until r.pageCount) {
                    r.openPage(i).use { p ->
                        val bmp=Bitmap.createBitmap(p.width,p.height,Bitmap.Config.ARGB_8888); p.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        addBitmapPage(out,bmp,pageNo++); bmp.recycle()
                    }
                }
            }
        }
    }
    return savePdf(context,out,"Merged")
}
private fun compressPdf(context:Context,uri:Uri):File {
    val out=PdfDocument(); var pageNo=1
    context.contentResolver.openFileDescriptor(uri,"r")!!.use { pfd ->
        PdfRenderer(pfd).use { r ->
            for(i in 0 until r.pageCount) r.openPage(i).use { p ->
                val maxW=1100
                val scale=minOf(1f,maxW.toFloat()/p.width)
                val bmp=Bitmap.createBitmap((p.width*scale).toInt().coerceAtLeast(1),(p.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.RGB_565)
                p.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); addBitmapPage(out,bmp,pageNo++); bmp.recycle()
            }
        }
    }
    return savePdf(context,out,"Compressed")
}
private fun pdfToImages(context:Context,uri:Uri,format:String):List<File> {
    val result=mutableListOf<File>()
    context.contentResolver.openFileDescriptor(uri,"r")!!.use { pfd ->
        PdfRenderer(pfd).use { r ->
            for(i in 0 until r.pageCount) r.openPage(i).use { p ->
                val bmp=Bitmap.createBitmap(p.width,p.height,Bitmap.Config.ARGB_8888); p.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val ext=if(format=="png")"png" else "jpg"; val f=File(context.filesDir,"PDF_page_${i+1}_${stamp()}.$ext")
                FileOutputStream(f).use { bmp.compress(if(format=="png")Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,if(format=="png")100 else 90,it) }
                bmp.recycle(); result+=f
            }
        }
    }
    return result
}
private fun docxToPdf(context:Context,uri:Uri):File {
    val sb=StringBuilder()
    context.contentResolver.openInputStream(uri)!!.use { input ->
        ZipInputStream(input).use { zip ->
            var e=zip.nextEntry
            while(e!=null) {
                if(e.name=="word/document.xml") {
                    val xml=zip.bufferedReader().readText()
                    sb.append(xml.replace(Regex("<w:tab[^>]*/>"),"    ").replace(Regex("</w:p>"),"\n").replace(Regex("<[^>]+>"),"").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">"))
                    break
                }
                e=zip.nextEntry
            }
        }
    }
    val bodyText=sb.toString().ifBlank{"No readable text found in DOCX."}
    val pdf=PdfDocument(); val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=14f}
    val lines=bodyText.lines().flatMap { wrapText(it,80) }
    var idx=0; var pageNo=1
    while(idx<lines.size) {
        val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,pageNo++).create()); page.canvas.drawColor(Color.WHITE)
        var y=50f
        while(idx<lines.size && y<800f){page.canvas.drawText(lines[idx++],40f,y,paint);y+=20f}
        pdf.finishPage(page)
    }
    return savePdf(context,pdf,"Docx")
}
private fun wrapText(s:String,n:Int):List<String> {
    if(s.length<=n)return listOf(s)
    val out=mutableListOf<String>(); var left=s
    while(left.length>n){var cut=left.lastIndexOf(' ',n);if(cut<1)cut=n;out+=left.substring(0,cut);left=left.substring(cut).trimStart()}
    out+=left; return out
}

private fun aiReadImage(bitmap:Bitmap,key:String,model:String):String {
    val baos=ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG,85,baos)
    val b64=Base64.encodeToString(baos.toByteArray(),Base64.NO_WRAP)
    val content=JSONArray()
        .put(JSONObject().put("type","input_text").put("text","Read this document image accurately. Return clean readable text, then a short summary. Preserve important numbers, dates, names and table-like data."))
        .put(JSONObject().put("type","input_image").put("image_url","data:image/jpeg;base64,$b64"))
    val input=JSONArray().put(JSONObject().put("role","user").put("content",content))
    val body=JSONObject().put("model",model).put("input",input).toString()
    val conn=(URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply{
        requestMethod="POST";doOutput=true;connectTimeout=30000;readTimeout=90000
        setRequestProperty("Authorization","Bearer $key");setRequestProperty("Content-Type","application/json")
    }
    conn.outputStream.use{it.write(body.toByteArray())}
    val code=conn.responseCode
    val raw=(if(code in 200..299)conn.inputStream else conn.errorStream).bufferedReader().readText()
    if(code !in 200..299) throw IllegalStateException(JSONObject(raw).optJSONObject("error")?.optString("message") ?: "HTTP $code")
    val json=JSONObject(raw)
    val output=json.optJSONArray("output") ?: return "No text returned."
    val sb=StringBuilder()
    for(i in 0 until output.length()){
        val contents=output.optJSONObject(i)?.optJSONArray("content") ?: continue
        for(j in 0 until contents.length()){
            val item=contents.optJSONObject(j) ?: continue
            val t=item.optString("text")
            if(t.isNotBlank()) sb.append(t).append("\n")
        }
    }
    return sb.toString().trim().ifBlank{"No readable text returned."}
}

private fun queryName(context:Context,uri:Uri):String {
    context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())return c.getString(0) ?: ""}
    return ""
}
private fun uniqueFile(dir:File,name:String):File {
    val clean=name.replace(Regex("[^A-Za-z0-9._ -]"),"_")
    var f=File(dir,clean); var i=1
    while(f.exists()){val base=clean.substringBeforeLast(".");val ext=clean.substringAfterLast(".","");f=File(dir,"${base}_$i${if(ext.isNotBlank())".$ext" else ""}");i++}
    return f
}
private fun formatSize(bytes:Long):String = when {
    bytes>=1024*1024 -> String.format(Locale.US,"%.1f MB",bytes/1048576.0)
    bytes>=1024 -> String.format(Locale.US,"%.1f kB",bytes/1024.0)
    else -> "$bytes B"
}
private fun formatDate(ms:Long):String=SimpleDateFormat("MM/dd HH:mm",Locale.getDefault()).format(Date(ms))
private fun stamp():String=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
private fun toast(context:Context,msg:String)=Toast.makeText(context,msg,Toast.LENGTH_SHORT).show()
