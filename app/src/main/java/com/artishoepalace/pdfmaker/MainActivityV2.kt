package com.artishoepalace.pdfmaker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivityV2 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent { PdfMakerV2(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfMakerV2(context: Context) {
    val prefs = remember { context.getSharedPreferences("pdf_maker", Context.MODE_PRIVATE) }
    var tab by remember { mutableIntStateOf(0) }; var toolsOpen by remember { mutableStateOf(false) }; var settingsOpen by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }; var search by remember { mutableStateOf("") }; var sort by remember { mutableStateOf(V2Sort.MODIFIED) }; var desc by remember { mutableStateOf(true) }; var sortOpen by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }; var aiBusy by remember { mutableStateOf(false) }; var aiResult by remember { mutableStateOf<String?>(null) }
    var promptTitle by remember { mutableStateOf<String?>(null) }; var promptValue by remember { mutableStateOf("") }; var pendingAction by remember { mutableStateOf<((android.net.Uri) -> Unit)?>(null) }
    val p = if (dark) V2Palette(Color(0xFF17130B), Color(0xFF241E12), Color(0xFF2D2517), Color(0xFFFFF7E3), Color(0xFFBAAD92), Color(0xFFD6A62A), Color(0xFFF0C85B)) else V2Palette(Color(0xFFF5EEDB), Color(0xFFFFFCF3), Color(0xFFF0E3BF), Color(0xFF2A2112), Color(0xFF7D725F), Color(0xFFB8860B), Color(0xFF6F4E00))

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) refresh++ else v2Toast(context, "Storage permission is needed to scan PDFs") }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val set = (prefs.getStringSet("scan_trees", emptySet()) ?: emptySet()).toMutableSet(); set += uri.toString(); prefs.edit().putStringSet("scan_trees", set).apply(); refresh++; v2Toast(context, "Folder added to PDF scan")
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT <= 32 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        else if (prefs.getBoolean("auto_scan", true)) refresh++
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { u -> if (u.isNotEmpty()) runCatching { v2ImagesToPdf(context, u) }.onSuccess { refresh++; v2Toast(context, "PDF created") }.onFailure { v2Toast(context, it.message ?: "Failed") } }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { runCatching { v2Import(context, it) }.onSuccess { refresh++; v2Toast(context, "PDF imported") } } }
    val genericPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { pendingAction?.invoke(it) }; pendingAction = null }
    val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { u -> if (u.size < 2) v2Toast(context, "Select at least 2 PDFs") else runCatching { v2Merge(context, u) }.onSuccess { refresh++; v2Toast(context, "PDFs merged") }.onFailure { v2Toast(context, it.message ?: "Merge failed") } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b -> b?.let { runCatching { v2BitmapToPdf(context, it, "scan") }.onSuccess { refresh++; v2Toast(context, "Scan saved") } } }
    val idCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b -> b?.let { runCatching { v2BitmapToPdf(context, it, "id_scan") }.onSuccess { refresh++; v2Toast(context, "ID scan saved") } } }
    val jpgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { runCatching { v2PdfToImages(context, it, "jpg") }.onSuccess { v2Toast(context, "JPG pages created") } } }
    val pngPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { runCatching { v2PdfToImages(context, it, "png") }.onSuccess { v2Toast(context, "PNG pages created") } } }
    val docxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { runCatching { v2OfficeToPdf(context, it, "docx") }.onSuccess { refresh++; v2Toast(context, "DOCX converted") }.onFailure { v2Toast(context, it.message ?: "DOCX failed") } } }
    val pptxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { runCatching { v2OfficeToPdf(context, it, "pptx") }.onSuccess { refresh++; v2Toast(context, "PPTX converted") }.onFailure { v2Toast(context, it.message ?: "PPTX failed") } } }
    val xlsxPdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { runCatching { v2OfficeToPdf(context, it, "xlsx") }.onSuccess { refresh++; v2Toast(context, "Excel converted") }.onFailure { v2Toast(context, it.message ?: "Excel failed") } } }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { runCatching { v2SpreadsheetToJson(context, it) }.onSuccess { f -> v2Toast(context, "JSON created"); v2ShareFile(context, f, "application/json") }.onFailure { v2Toast(context, it.message ?: "JSON failed") } } }

    fun launchAi(block: () -> String) { aiBusy = true; Thread { val r = runCatching(block).getOrElse { "AI Scan failed: ${it.message}" }; (context as MainActivityV2).runOnUiThread { aiBusy = false; aiResult = r } }.start() }
    val aiCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b ->
        b ?: return@rememberLauncherForActivityResult; val key = prefs.getString("openai_key", "").orEmpty(); if (key.isBlank()) { settingsOpen = true; v2Toast(context, "Add API key first") } else launchAi { v2AiReadImage(b, key, prefs.getString("openai_model", "gpt-5.4").orEmpty(), prefs.getBoolean("ai_json", false)) }
    }
    val aiImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u ?: return@rememberLauncherForActivityResult; val key = prefs.getString("openai_key", "").orEmpty(); if (key.isBlank()) { settingsOpen = true; v2Toast(context, "Add API key first") } else launchAi { val b = context.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it) }; v2AiReadImage(b, key, prefs.getString("openai_model", "gpt-5.4").orEmpty(), prefs.getBoolean("ai_json", false)) }
    }
    val aiPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u ?: return@rememberLauncherForActivityResult; val key = prefs.getString("openai_key", "").orEmpty(); if (key.isBlank()) { settingsOpen = true; v2Toast(context, "Add API key first") } else launchAi { v2AiReadPdf(context, u, key, prefs.getString("openai_model", "gpt-5.4").orEmpty(), prefs.getBoolean("ai_json", false)) }
    }

    fun pickPdf(a: (android.net.Uri) -> Unit) { pendingAction = a; genericPdf.launch(arrayOf("application/pdf")) }
    fun prompt(title: String, default: String = "", a: (android.net.Uri, String) -> Unit) { promptTitle = title; promptValue = default; pendingAction = { u -> a(u, promptValue) } }

    val convert = listOf(
        V2Tool("Image to PDF", Icons.Default.PictureAsPdf, Color(0xFFFF4D6D)) { imagePicker.launch("image/*") },
        V2Tool("DOCX to PDF", Icons.Default.Description, Color(0xFF4E82FF)) { docxPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) },
        V2Tool("PPTX to PDF", Icons.Default.Slideshow, Color(0xFFFF8B2B)) { pptxPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation")) },
        V2Tool("Excel to PDF", Icons.Default.TableChart, Color(0xFF20A98B)) { xlsxPdfPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv")) },
        V2Tool("PDF to JPG", Icons.Default.Image, Color(0xFFFF4D6D)) { jpgPicker.launch(arrayOf("application/pdf")) },
        V2Tool("PDF to PNG", Icons.Default.Photo, Color(0xFF7A5AF8)) { pngPicker.launch(arrayOf("application/pdf")) },
        V2Tool("PDF to Word", Icons.Default.Article, Color(0xFF4E82FF)) { pickPdf { u -> runCatching { v2PdfToDocx(context, u) }.onSuccess { v2ShareFile(context, it, "application/vnd.openxmlformats-officedocument.wordprocessingml.document") }.onFailure { v2Toast(context, it.message ?: "Failed") } } },
        V2Tool("PDF to Text", Icons.Default.TextSnippet, Color(0xFF20A98B)) { pickPdf { u -> runCatching { v2PdfToText(context, u) }.onSuccess { v2ShareFile(context, it, "text/plain") }.onFailure { v2Toast(context, it.message ?: "Failed") } } },
        V2Tool("Excel to JSON", Icons.Default.DataObject, p.gold) { jsonPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/tab-separated-values")) }
    )
    val popular = listOf(
        V2Tool("Smart Scan", Icons.Default.DocumentScanner, Color(0xFF4E82FF)) { camera.launch(null) }, V2Tool("Scan ID Card", Icons.Default.Badge, Color(0xFF20A98B)) { idCamera.launch(null) },
        V2Tool("AI Scan Camera", Icons.Default.AutoAwesome, p.gold) { aiCamera.launch(null) }, V2Tool("AI Scan Image", Icons.Default.AutoFixHigh, Color(0xFF7A5AF8)) { aiImage.launch("image/*") },
        V2Tool("AI Read PDF", Icons.Default.Psychology, p.gold) { aiPdf.launch(arrayOf("application/pdf")) }, V2Tool("Import PDF", Icons.Default.Folder, Color(0xFFFFA928)) { importPicker.launch(arrayOf("application/pdf")) },
        V2Tool("Add Scan Folder", Icons.Default.CreateNewFolder, Color(0xFF20A98B)) { folderPicker.launch(null) }, V2Tool("Rescan Files", Icons.Default.Refresh, Color(0xFF4E82FF)) { refresh++; v2Toast(context, "Scanning in background") }
    )
    val edit = listOf(
        V2Tool("Merge PDF", Icons.Default.CallMerge, Color(0xFFFF8B2B)) { mergePicker.launch(arrayOf("application/pdf")) }, V2Tool("Compress", Icons.Default.Compress, Color(0xFFFF4D6D)) { pickPdf { u -> runCatching { v2Compress(context, u) }.onSuccess { refresh++; v2Toast(context, "Compressed") } } },
        V2Tool("Split PDF", Icons.Default.ContentCut, Color(0xFF4E82FF)) { pickPdf { u -> runCatching { v2Split(context, u) }.onSuccess { refresh++; v2Toast(context, "Split into ${it.size} PDFs") } } }, V2Tool("Rotate Pages", Icons.Default.RotateRight, Color(0xFF7A5AF8)) { pickPdf { u -> runCatching { v2Rotate(context, u) }.onSuccess { refresh++; v2Toast(context, "Rotated") } } },
        V2Tool("Watermark", Icons.Default.BrandingWatermark, p.gold) { prompt("Watermark text", "CONFIDENTIAL") { u, t -> runCatching { v2TextOverlay(context, u, t, true) }.onSuccess { refresh++; v2Toast(context, "Watermark added") } } }, V2Tool("Add Text", Icons.Default.TextFields, Color(0xFF20A98B)) { prompt("Text to add") { u, t -> runCatching { v2TextOverlay(context, u, t, false) }.onSuccess { refresh++; v2Toast(context, "Text added") } } },
        V2Tool("Lock PDF", Icons.Default.Lock, Color(0xFF4E82FF)) { prompt("Set PDF password") { u, t -> runCatching { v2Lock(context, u, t) }.onSuccess { refresh++; v2Toast(context, "Locked PDF created") }.onFailure { v2Toast(context, it.message ?: "Failed") } } }, V2Tool("Unlock PDF", Icons.Default.LockOpen, Color(0xFFFF8B2B)) { prompt("PDF password") { u, t -> runCatching { v2Unlock(context, u, t) }.onSuccess { refresh++; v2Toast(context, "Unlocked PDF created") }.onFailure { v2Toast(context, "Wrong password or unsupported PDF") } } },
        V2Tool("Remove Metadata", Icons.Default.CleaningServices, Color(0xFF20A98B)) { pickPdf { u -> runCatching { v2Clean(context, u) }.onSuccess { refresh++; v2Toast(context, "Clean PDF created") } } }
    )

    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = p.gold) else lightColorScheme(primary = p.gold, surface = p.card, background = p.bg)) {
        Scaffold(containerColor = p.bg, bottomBar = { if (!toolsOpen && !settingsOpen) V2BottomNav(tab, p) { if (it == 2) imagePicker.launch("image/*") else tab = it } }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).background(p.bg)) {
                when { settingsOpen -> V2Settings(context, p, dark, { dark = it; prefs.edit().putBoolean("dark", it).apply() }, { settingsOpen = false }, { refresh++ }, { folderPicker.launch(null) }); toolsOpen -> V2Tools(p, { toolsOpen = false }, convert, popular, edit); tab == 0 -> V2Home(context, refresh, p, { settingsOpen = true }, { search = it }, { imagePicker.launch("image/*") }, { camera.launch(null) }, { importPicker.launch(arrayOf("application/pdf")) }, { pickPdf { u -> runCatching { v2Compress(context, u) }.onSuccess { refresh++ } } }, { jpgPicker.launch(arrayOf("application/pdf")) }, { mergePicker.launch(arrayOf("application/pdf")) }, { docxPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }, { toolsOpen = true }, { refresh++ }); else -> V2Files(context, refresh, p, sort, desc, search, { search = it }, { settingsOpen = true }, { sortOpen = true }, { refresh++ }) }
                if (aiBusy) V2Loading(p)
            }
        }
        if (sortOpen) V2SortSheet(p, sort, desc, { sort = it }, { desc = it }, { sortOpen = false })
        aiResult?.let { r -> AlertDialog(onDismissRequest = { aiResult = null }, title = { Text("AI Scan Result") }, text = { androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 450.dp)) { item { Text(r) } } }, confirmButton = { TextButton(onClick = { aiResult = null }) { Text("Done") } }) }
        promptTitle?.let { title -> AlertDialog(onDismissRequest = { promptTitle = null; pendingAction = null }, title = { Text(title) }, text = { OutlinedTextField(promptValue, { promptValue = it }, singleLine = true, label = { Text(title) }, visualTransformation = if (title.contains("password", true)) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None) }, confirmButton = { Button(onClick = { if (promptValue.isBlank()) v2Toast(context, "Enter a value") else { promptTitle = null; genericPdf.launch(arrayOf("application/pdf")) } }) { Text("Select PDF") } }, dismissButton = { TextButton(onClick = { promptTitle = null; pendingAction = null }) { Text("Cancel") } }) }
    }
}

fun v2Toast(context: Context, s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
