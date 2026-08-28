package com.artishoepalace.pdfmaker

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun v2ListDocs(context: Context): List<V2Doc> {
    val map = linkedMapOf<String, V2Doc>()
    context.filesDir.listFiles()?.filter { it.extension.equals("pdf", true) }?.forEach { f -> map[f.absolutePath] = V2Doc(f, null, f.name, f.length(), f.lastModified(), v2PageCount(f)) }
    runCatching {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = mutableListOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED)
        if (Build.VERSION.SDK_INT <= 28) projection += MediaStore.Files.FileColumns.DATA
        context.contentResolver.query(collection, projection.toTypedArray(), "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?", arrayOf("application/pdf", "%.pdf"), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID); val n = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME); val s = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE); val m = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED); val d = if (Build.VERSION.SDK_INT <= 28) c.getColumnIndex(MediaStore.Files.FileColumns.DATA) else -1
            while (c.moveToNext()) {
                val name = c.getString(n) ?: continue; val uri = ContentUris.withAppendedId(collection, c.getLong(id)); val path = if (d >= 0) c.getString(d) else null; val file = path?.let(::File)?.takeIf { it.exists() }; val key = file?.absolutePath ?: uri.toString()
                map[key] = V2Doc(file, uri, name, c.getLong(s), c.getLong(m) * 1000L, file?.let(::v2PageCount) ?: 0)
            }
        }
    }
    val prefs = context.getSharedPreferences("pdf_maker", Context.MODE_PRIVATE)
    (prefs.getStringSet("scan_trees", emptySet()) ?: emptySet()).forEach { runCatching { v2ScanTree(context, Uri.parse(it), map, 0) } }
    return map.values.sortedByDescending { it.modified }
}

private fun v2ScanTree(context: Context, treeUri: Uri, map: MutableMap<String, V2Doc>, depth: Int) {
    if (depth > 20) return
    val docId = if (depth == 0) DocumentsContract.getTreeDocumentId(treeUri) else DocumentsContract.getDocumentId(treeUri)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
    val p = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    context.contentResolver.query(children, p, null, null, null)?.use { c ->
        val id = c.getColumnIndexOrThrow(p[0]); val name = c.getColumnIndexOrThrow(p[1]); val mime = c.getColumnIndexOrThrow(p[2]); val size = c.getColumnIndex(p[3]); val mod = c.getColumnIndex(p[4])
        while (c.moveToNext()) {
            val child = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(id)); val nm = c.getString(name) ?: "file"; val mt = c.getString(mime) ?: ""
            if (mt == DocumentsContract.Document.MIME_TYPE_DIR) v2ScanTree(context, child, map, depth + 1)
            else if (mt == "application/pdf" || nm.endsWith(".pdf", true)) map[child.toString()] = V2Doc(null, child, nm, if (size >= 0) c.getLong(size) else 0, if (mod >= 0) c.getLong(mod) else 0)
        }
    }
}

private fun v2PageCount(file: File): Int = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { p -> PdfRenderer(p).use { it.pageCount } } }.getOrDefault(0)
fun v2OpenDoc(context: Context, doc: V2Doc) { val u = doc.uri ?: doc.file?.let { FileProvider.getUriForFile(context, "${context.packageName}.files", it) } ?: return; runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply { setDataAndType(u, "application/pdf"); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } }
fun v2ShareDoc(context: Context, doc: V2Doc) { val u = doc.uri ?: doc.file?.let { FileProvider.getUriForFile(context, "${context.packageName}.files", it) } ?: return; context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(android.content.Intent.EXTRA_STREAM, u); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share PDF")) }
fun v2ShareFile(context: Context, file: File, mime: String) { val u = FileProvider.getUriForFile(context, "${context.packageName}.files", file); context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = mime; putExtra(android.content.Intent.EXTRA_STREAM, u); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share file")) }

fun v2ImagesToPdf(context: Context, uris: List<Uri>): File { val pdf = PdfDocument(); uris.forEachIndexed { i, u -> context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it)?.let { b -> v2AddPage(pdf, b, i + 1); b.recycle() } } }; return v2SavePdf(context, pdf, "Image_to_PDF") }
fun v2BitmapToPdf(context: Context, bmp: Bitmap, prefix: String): File { val pdf = PdfDocument(); v2AddPage(pdf, bmp, 1); return v2SavePdf(context, pdf, prefix) }
private fun v2AddPage(pdf: PdfDocument, bmp: Bitmap, pageNo: Int) { val pw = 595; val ph = 842; val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create()); val scale = minOf(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height); val w = bmp.width * scale; val h = bmp.height * scale; page.canvas.drawColor(Color.WHITE); page.canvas.drawBitmap(bmp, null, android.graphics.RectF((pw - w) / 2, (ph - h) / 2, (pw + w) / 2, (ph + h) / 2), Paint(Paint.ANTI_ALIAS_FLAG)); pdf.finishPage(page) }
private fun v2SavePdf(context: Context, pdf: PdfDocument, prefix: String): File { val f = File(context.filesDir, "${prefix}_${v2Stamp()}.pdf"); FileOutputStream(f).use { pdf.writeTo(it) }; pdf.close(); return f }
fun v2Import(context: Context, uri: Uri): File { val f = v2Unique(context.filesDir, v2QueryName(context, uri).ifBlank { "import_${v2Stamp()}.pdf" }); context.contentResolver.openInputStream(uri)!!.use { a -> FileOutputStream(f).use { a.copyTo(it) } }; return f }
private fun v2Temp(context: Context, uri: Uri): File { val f = File(context.cacheDir, "src_${System.nanoTime()}.pdf"); context.contentResolver.openInputStream(uri)!!.use { a -> FileOutputStream(f).use { a.copyTo(it) } }; return f }

fun v2Merge(context: Context, uris: List<Uri>): File { val temps = uris.map { v2Temp(context, it) }; val out = File(context.filesDir, "Merged_${v2Stamp()}.pdf"); PDFMergerUtility().apply { temps.forEach(::addSource); destinationFileName = out.absolutePath; mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly()) }; temps.forEach(File::delete); return out }
fun v2Compress(context: Context, uri: Uri): File { val out = PdfDocument(); var pageNo = 1; context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd -> PdfRenderer(fd).use { r -> for (i in 0 until r.pageCount) r.openPage(i).use { p -> val scale = minOf(1f, 1000f / p.width); val b = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); v2AddPage(out, b, pageNo++); b.recycle() } } }; return v2SavePdf(context, out, "Compressed") }
fun v2Split(context: Context, uri: Uri): List<File> { val src = v2Temp(context, uri); val out = mutableListOf<File>(); PDDocument.load(src).use { d -> for (i in 0 until d.numberOfPages) { PDDocument().use { s -> s.importPage(d.getPage(i)); val f = File(context.filesDir, "Split_${i + 1}_${v2Stamp()}.pdf"); s.save(f); out += f } } }; src.delete(); return out }
fun v2Rotate(context: Context, uri: Uri): File { val src = v2Temp(context, uri); val out = File(context.filesDir, "Rotated_${v2Stamp()}.pdf"); PDDocument.load(src).use { d -> for (i in 0 until d.numberOfPages) d.getPage(i).rotation = (d.getPage(i).rotation + 90) % 360; d.save(out) }; src.delete(); return out }
fun v2TextOverlay(context: Context, uri: Uri, text: String, watermark: Boolean): File { val src = v2Temp(context, uri); val out = File(context.filesDir, "${if (watermark) "Watermarked" else "Text"}_${v2Stamp()}.pdf"); PDDocument.load(src).use { d -> for (i in 0 until d.numberOfPages) { val p = d.getPage(i); PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true).use { c -> c.beginText(); c.setFont(PDType1Font.HELVETICA_BOLD, if (watermark) 28f else 14f); c.setNonStrokingColor(if (watermark) 184 else 40, if (watermark) 134 else 40, if (watermark) 11 else 40); c.newLineAtOffset(42f, if (watermark) p.mediaBox.height / 2 else p.mediaBox.height - 48f); c.showText(text.take(120).replace("\n", " ")); c.endText() } }; d.save(out) }; src.delete(); return out }
fun v2Lock(context: Context, uri: Uri, password: String): File { val src = v2Temp(context, uri); val out = File(context.filesDir, "Locked_${v2Stamp()}.pdf"); PDDocument.load(src).use { d -> d.protect(StandardProtectionPolicy(password + "_owner", password, AccessPermission()).apply { encryptionKeyLength = 128 }); d.save(out) }; src.delete(); return out }
fun v2Unlock(context: Context, uri: Uri, password: String): File { val src = v2Temp(context, uri); val out = File(context.filesDir, "Unlocked_${v2Stamp()}.pdf"); PDDocument.load(src, password).use { d -> d.setAllSecurityToBeRemoved(true); d.save(out) }; src.delete(); return out }
fun v2Clean(context: Context, uri: Uri): File { val src = v2Temp(context, uri); val out = File(context.filesDir, "Clean_${v2Stamp()}.pdf"); PDDocument.load(src).use { d -> d.documentInformation.apply { author = null; title = null; subject = null; keywords = null; creator = null; producer = null }; d.save(out) }; src.delete(); return out }
fun v2PdfToText(context: Context, uri: Uri): File { val src = v2Temp(context, uri); val text = PDDocument.load(src).use { PDFTextStripper().getText(it) }; src.delete(); return File(context.filesDir, "PDF_Text_${v2Stamp()}.txt").apply { writeText(text) } }
fun v2PdfToDocx(context: Context, uri: Uri): File { val textFile = v2PdfToText(context, uri); val text = textFile.readText(); textFile.delete(); val out = File(context.filesDir, "PDF_Word_${v2Stamp()}.docx"); ZipOutputStream(FileOutputStream(out)).use { z -> fun e(n: String, b: String) { z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry() }; e("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""); e("_rels/.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""); val ps = text.lines().joinToString("") { "<w:p><w:r><w:t xml:space=\"preserve\">${v2Xml(it)}</w:t></w:r></w:p>" }; e("word/document.xml", """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$ps<w:sectPr/></w:body></w:document>""") }; return out }
fun v2PdfToImages(context: Context, uri: Uri, format: String): List<File> { val out = mutableListOf<File>(); context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd -> PdfRenderer(fd).use { r -> for (i in 0 until r.pageCount) r.openPage(i).use { p -> val b = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); val ext = if (format == "png") "png" else "jpg"; val f = File(context.filesDir, "PDF_page_${i + 1}_${v2Stamp()}.$ext"); FileOutputStream(f).use { b.compress(if (format == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (format == "png") 100 else 90, it) }; b.recycle(); out += f } } }; return out }

fun v2OfficeToPdf(context: Context, uri: Uri, type: String): File { val text = when (type) { "docx" -> v2ZipText(context.contentResolver.openInputStream(uri)!!) { it == "word/document.xml" }; "pptx" -> v2ZipText(context.contentResolver.openInputStream(uri)!!) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }; "xlsx" -> v2SheetRows(context, uri).joinToString("\n") { it.joinToString(" | ") }; else -> "" }; return v2TextPdf(context, text.ifBlank { "No readable text found." }, type.uppercase()) }
private fun v2ZipText(input: InputStream, match: (String) -> Boolean): String { val sb = StringBuilder(); ZipInputStream(input).use { z -> var e = z.nextEntry; while (e != null) { if (match(e.name)) sb.append(z.bufferedReader().readText().replace(Regex("</(?:w:p|a:p)>"), "\n").replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")).append('\n'); e = z.nextEntry } }; return sb.toString() }
private fun v2TextPdf(context: Context, text: String, prefix: String): File { val pdf = PdfDocument(); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 13f }; val lines = text.lines().flatMap { v2Wrap(it, 82) }; var idx = 0; var no = 1; do { val p = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, no++).create()); p.canvas.drawColor(Color.WHITE); var y = 48f; while (idx < lines.size && y < 800) { p.canvas.drawText(lines[idx++], 36f, y, paint); y += 18 }; pdf.finishPage(p) } while (idx < lines.size); return v2SavePdf(context, pdf, prefix) }
fun v2SpreadsheetToJson(context: Context, uri: Uri): File { val rows = v2SheetRows(context, uri); val a = JSONArray(); if (rows.isNotEmpty()) { val headers = rows.first().mapIndexed { i, s -> s.ifBlank { "column_${i + 1}" } }; rows.drop(1).forEach { r -> val o = JSONObject(); headers.forEachIndexed { i, h -> o.put(h, r.getOrElse(i) { "" }) }; a.put(o) } }; return File(context.filesDir, "Spreadsheet_${v2Stamp()}.json").apply { writeText(a.toString(2)) } }
private fun v2SheetRows(context: Context, uri: Uri): List<List<String>> { val name = v2QueryName(context, uri).lowercase(); if (name.endsWith(".csv") || name.endsWith(".tsv")) { val sep = if (name.endsWith(".tsv")) '\t' else ','; return context.contentResolver.openInputStream(uri)!!.bufferedReader().readLines().map { v2Delimited(it, sep) } }; val shared = mutableListOf<String>(); val sheets = mutableListOf<String>(); context.contentResolver.openInputStream(uri)!!.use { input -> ZipInputStream(input).use { z -> var e = z.nextEntry; while (e != null) { if (e.name == "xl/sharedStrings.xml") Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(z.bufferedReader().readText()).forEach { shared += it.groupValues[1] } else if (e.name.startsWith("xl/worksheets/sheet") && e.name.endsWith(".xml")) sheets += z.bufferedReader().readText(); e = z.nextEntry } } }; val rows = mutableListOf<List<String>>(); sheets.forEach { x -> Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(x).forEach { rr -> val cells = mutableListOf<String>(); Regex("<c([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL).findAll(rr.groupValues[1]).forEach { c -> val raw = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(c.groupValues[2])?.groupValues?.get(1) ?: Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).find(c.groupValues[2])?.groupValues?.get(1) ?: ""; cells += if (c.groupValues[1].contains("t=\"s\"")) shared.getOrElse(raw.toIntOrNull() ?: -1) { raw } else raw }; rows += cells } }; return rows }
private fun v2Delimited(line: String, sep: Char): List<String> { val out = mutableListOf<String>(); val b = StringBuilder(); var q = false; var i = 0; while (i < line.length) { val ch = line[i]; when { ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { b.append('"'); i++ }; ch == '"' -> q = !q; ch == sep && !q -> { out += b.toString(); b.clear() }; else -> b.append(ch) }; i++ }; out += b.toString(); return out }

fun v2QueryName(context: Context, uri: Uri): String { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" }; return "" }
fun v2Size(bytes: Long): String = when { bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0); bytes >= 1024 -> String.format(Locale.US, "%.1f kB", bytes / 1024.0); else -> "$bytes B" }
fun v2Date(ms: Long): String = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(ms))
private fun v2Stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
private fun v2Unique(dir: File, name: String): File { val clean = name.replace(Regex("[^A-Za-z0-9._ -]"), "_"); var f = File(dir, clean); var i = 1; while (f.exists()) { val b = clean.substringBeforeLast("."); val e = clean.substringAfterLast(".", ""); f = File(dir, "${b}_$i${if (e.isNotBlank()) ".$e" else ""}"); i++ }; return f }
private fun v2Wrap(s: String, n: Int): List<String> { if (s.length <= n) return listOf(s); val out = mutableListOf<String>(); var l = s; while (l.length > n) { var c = l.lastIndexOf(' ', n); if (c < 1) c = n; out += l.substring(0, c); l = l.substring(c).trimStart() }; out += l; return out }
private fun v2Xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
