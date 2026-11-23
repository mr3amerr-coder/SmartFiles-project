package com.example.smartfiles

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import android.app.usage.StorageStatsManager
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import kotlinx.coroutines.*
import java.io.File

@Composable fun SmartTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current; val dark = isSystemInDarkTheme()
    val cs = if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
             else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = cs, content = content)
}

fun shareFile(c: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fp", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
    } catch (e: Exception) { toast(c, "Couldn’t share this file") }
}

// ---------- File operations ----------

/** First free name in [dir]: "a.txt" -> "a (1).txt", or "a (restored).txt" when [tag] is given. */
fun freeName(dir: File, name: String, isDir: Boolean, tag: String = ""): File {
    var t = File(dir, name)
    if (!t.exists()) return t
    val ext = if (isDir || File(name).extension.isEmpty()) "" else "." + File(name).extension
    val base = if (isDir) name else File(name).nameWithoutExtension
    var i = 1
    while (t.exists()) {
        t = File(dir, if (tag.isEmpty()) "$base ($i)$ext" else "$base ($tag${if (i > 1) " $i" else ""})$ext")
        i++
    }
    return t
}

/** Returns null on success, or a message that explains what went wrong. */
fun renameFile(f: File, name: String): String? {
    val n = name.trim()
    if (n.isEmpty() || n == "." || n == ".." || '/' in n) return "Enter a valid name"
    val t = File(f.parentFile, n)
    // Storage is case-insensitive, so "a.txt" -> "A.txt" is a rename, not a clash.
    if (t.exists() && !n.equals(f.name, ignoreCase = true)) return "“$n” already exists here"
    return if (f.renameTo(t)) null else "Couldn’t rename"
}

fun makeFolder(dir: File, name: String): String? {
    val n = name.trim()
    if (n.isEmpty() || n == "." || n == ".." || '/' in n) return "Enter a valid name"
    val t = File(dir, n)
    if (t.exists()) return "“$n” already exists here"
    return if (t.mkdirs()) null else "Couldn’t create folder"
}

/** Copies or moves each file into [dir]. Never overwrites; returns how many succeeded. */
fun pasteInto(dir: File, files: List<File>, cut: Boolean): Int {
    var ok = 0
    for (f in files) {
        if (!f.exists()) continue
        if (f.isDirectory && (dir.path == f.path || dir.path.startsWith(f.path + "/"))) continue
        if (cut && f.parentFile?.path == dir.path) { ok++; continue }   // already there
        val t = freeName(dir, f.name, f.isDirectory)
        val done = try {
            if (cut && f.renameTo(t)) true
            else if (f.copyRecursively(t)) { if (cut) f.deleteRecursively(); true }
            else { t.deleteRecursively(); false }
        } catch (e: Exception) { t.deleteRecursively(); false }
        if (done) ok++
    }
    return ok
}

// ---------- Recycle Bin: <volume>/.SmartTrash/<id>/{origin,data} ----------
// Each storage volume keeps its own Bin, so deleting from an SD card is an instant rename
// instead of a slow copy onto internal storage.
const val TRASH = ".SmartTrash"
private const val DAY = 86_400_000L

fun volumeRootOf(f: File): File =
    Session.roots.filter { f.path == it.path || f.path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
        ?: Environment.getExternalStorageDirectory()

fun trashRoots(roots: List<File> = Session.roots): List<File> =
    (roots + Environment.getExternalStorageDirectory()).distinctBy { it.path }.map { File(it, TRASH) }

fun moveToTrash(f: File): Boolean {
    val d = File(File(volumeRootOf(f), TRASH), System.nanoTime().toString())
    if (!d.mkdirs()) return false
    if (runCatching { File(d, "origin").writeText(f.path) }.isFailure) { d.deleteRecursively(); return false }
    val t = File(d, "data")
    if (f.renameTo(t)) return true
    val copied = try { f.copyRecursively(t) } catch (e: Exception) { false }
    if (!copied) { d.deleteRecursively(); return false }
    f.deleteRecursively()
    // The full copy is safe in the Bin; only report success if the original is really gone.
    return !f.exists()
}

class Bin(val dir: File) {
    private val originFile = File(dir, "origin")
    val orig = originFile.takeIf { it.exists() }?.readText() ?: dir.name
    val data = File(dir, "data")
    val name: String get() = File(orig).name
    /** When it was deleted (the origin file is written once, at deletion time). */
    val time: Long = if (originFile.exists()) originFile.lastModified() else dir.lastModified()
}

fun binItems(): List<Bin> =
    trashRoots().flatMap { it.listFiles()?.toList() ?: emptyList() }.filter { File(it, "data").exists() }.map { Bin(it) }.sortedByDescending { it.time }

fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

fun purgeOldBin(roots: List<File> = Session.roots) {
    val limit = System.currentTimeMillis() - 30 * DAY
    trashRoots(roots).forEach { r ->
        r.listFiles()?.forEach { d ->
            val o = File(d, "origin")
            if ((if (o.exists()) o.lastModified() else d.lastModified()) < limit) d.deleteRecursively()
        }
    }
}

fun restore(b: Bin): Boolean {
    val orig = File(b.orig)
    val parent = orig.parentFile ?: return false
    parent.mkdirs()
    val t = freeName(parent, orig.name, b.data.isDirectory, "restored")
    if (b.data.renameTo(t)) { b.dir.deleteRecursively(); return true }
    if (try { b.data.copyRecursively(t) } catch (e: Exception) { false }) { b.dir.deleteRecursively(); return true }
    t.deleteRecursively()   // t did not exist before, so this only removes a partial copy
    return false
}

@Composable fun TrashScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var empty by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf<List<Bin>?>(null) }
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(tick) {
        val r = withContext(Dispatchers.IO) { val l = binItems(); l to l.associate { it.dir.path to dirSize(it.data) } }
        loaded = r.first; sizes = r.second
    }
    fun act(block: () -> String?) {
        scope.launch {
            busy = true
            val m = withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }
            busy = false; m?.let { toast(ctx, it) }; tick++
        }
    }
    val bin = loaded ?: emptyList()
    val total = sizes.values.sum()
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(if (loaded == null) "Recycle Bin" else "Recycle Bin · ${bin.size} items · ${fmt(total)}", style = MaterialTheme.typography.titleMedium)
        Text("Deleted items stay here for 30 days. Empty the Bin to free the space now.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { empty = true }, enabled = bin.isNotEmpty() && !busy, modifier = Modifier.padding(vertical = 8.dp)) { Text("Empty Bin & free space") }
        if (busy || loaded == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (loaded != null && bin.isEmpty()) Text("The Bin is empty.")
        LazyColumn(Modifier.weight(1f)) {
            items(bin, key = { it.dir.path }) { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(b.orig.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val left = (30 - (now - b.time) / DAY).coerceAtLeast(0)
                        Text("${fmt(sizes[b.dir.path] ?: 0L)} · $left days left", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton({ act { if (restore(b)) null else "Couldn’t restore. Check that the original folder is available." } }, enabled = !busy) { Text("Restore") }
                    TextButton({ act { b.dir.deleteRecursively(); null } }, enabled = !busy) { Text("Delete") }
                }
            }
        }
    }
    if (empty) AlertDialog({ empty = false }, title = { Text("Empty the Bin?") }, text = { Text("${bin.size} items will be permanently deleted.") },
        confirmButton = { TextButton({ empty = false; act { trashRoots().forEach { it.deleteRecursively() }; null } }) { Text("Empty") } },
        dismissButton = { TextButton({ empty = false }) { Text("Cancel") } })
}

// ---------- Session state (survives tab switches and rotation) ----------
object Session {
    var dir by mutableStateOf(File(Environment.getExternalStorageDirectory().path))
    var cut by mutableStateOf(false)
    var roots by mutableStateOf(listOf(File(Environment.getExternalStorageDirectory().path)))
    val clip = mutableStateListOf<File>()

    // Clean tab: scan and delete run in a process-wide scope, so switching tabs or rotating doesn't throw the work away.
    var junk by mutableStateOf<List<Junk>?>(null)
    val junkSel = mutableStateListOf<File>()
    var scanning by mutableStateOf(false)
    var cleaning by mutableStateOf(false)
    var note by mutableStateOf<String?>(null)
    var stats by mutableStateOf<List<Pair<String, Long>>?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startScan() {
        if (scanning || cleaning) return
        scanning = true; note = null
        scope.launch {
            val r = runCatching { scan() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                junk = r; junkSel.clear(); junkSel.addAll(r.filter { it.safe }.map { it.f }); scanning = false
            }
        }
    }

    fun cleanSelected(permanent: Boolean) {
        if (scanning || cleaning || junkSel.isEmpty()) return
        val files = junkSel.toList(); cleaning = true
        scope.launch {
            var n = 0; var bytes = 0L
            for (f in files) {
                val s = f.length()
                val ok = if (permanent) f.deleteRecursively() else moveToTrash(f)
                if (ok) { n++; bytes += s }
            }
            withContext(Dispatchers.Main) {
                junk = junk?.filter { it.f.exists() }
                val keep = junkSel.filter { it.exists() }; junkSel.clear(); junkSel.addAll(keep)
                note = if (permanent) "Deleted $n files and freed ${fmt(bytes)}."
                       else "Moved $n files to the Bin. ${fmt(bytes)} is freed once you empty the Bin."
                cleaning = false
            }
        }
    }
}

// ---------- Storage breakdown ----------
fun breakdown(): List<Pair<String, Long>> {
    val m = linkedMapOf("🖼️ Images" to 0L, "🎬 Videos" to 0L, "🎵 Audio" to 0L, "📄 Documents" to 0L, "📦 APKs & archives" to 0L, "📎 Other" to 0L)
    Session.roots.asSequence().flatMap { r -> r.walkTopDown().onEnter { it.name != TRASH } }.filter { it.isFile }.forEach { f ->
        val k = when (f.extension.lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "heic" -> "🖼️ Images"
            "mp4", "mkv", "avi", "mov", "3gp" -> "🎬 Videos"
            "mp3", "wav", "m4a", "flac", "ogg", "opus" -> "🎵 Audio"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md" -> "📄 Documents"
            "apk", "zip", "rar", "7z" -> "📦 APKs & archives"
            else -> "📎 Other"
        }
        m[k] = m.getValue(k) + f.length()
    }
    return m.toList().sortedByDescending { it.second }
}

@Composable fun BreakdownCard() {
    // Shows the last result straight away and refreshes it in the background.
    LaunchedEffect(Unit) { val r = withContext(Dispatchers.IO) { breakdown() }; Session.stats = r }
    val d = Session.stats
    if (d == null) { Text("Analyzing storage…", style = MaterialTheme.typography.bodySmall); return }
    val max = (d.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    Text("What’s taking space", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
    d.forEach { (n, s) ->
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n, Modifier.width(140.dp), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { s.toFloat() / max }, Modifier.weight(1f))
            Text(fmt(s), Modifier.padding(start = 8.dp).width(64.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- Real app size (app + data + cache) when usage access is granted ----------
fun appSize(c: Context, a: ApplicationInfo): Long = runCatching {
    val s = (c.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager)
        .queryStatsForPackage(StorageManager.UUID_DEFAULT, a.packageName, Process.myUserHandle())
    s.appBytes + s.dataBytes
}.getOrElse { File(a.sourceDir).length() }

// ---------- Storage volumes (internal, SD card, USB drives) ----------
class Vol(val name: String, val dir: File)

fun volumes(c: Context): List<Vol> {
    val sm = c.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    return sm.storageVolumes.filter { it.state == Environment.MEDIA_MOUNTED && it.directory != null }
        .map { Vol(if (it.isPrimary) "📱 Internal storage" else "💾 " + (it.getDescription(c) ?: "External storage"), it.directory!!) }
}

fun freeOf(f: File): String = runCatching { StatFs(f.path).let { "${fmt(it.availableBytes)} free of ${fmt(it.totalBytes)}" } }.getOrDefault("")
