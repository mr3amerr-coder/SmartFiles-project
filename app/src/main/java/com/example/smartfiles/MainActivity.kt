package com.example.smartfiles

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        setContent {
            SmartTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

fun fmt(b: Long): String {
    if (b < 1024) return "$b B"
    var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < 3) { v /= 1024; i++ }
    return "%.1f %cB".format(v, "KMGT"[i])
}

fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_SHORT).show()

/** Starts a system screen; some phones don't have every settings page. */
fun safeStart(c: Context, i: Intent) {
    try { c.startActivity(i) } catch (e: Exception) { toast(c, "This screen isn’t available on your phone") }
}

fun openFile(c: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fp", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: ActivityNotFoundException) { toast(c, "No app can open this file")
    } catch (e: Exception) { toast(c, "Couldn’t open this file") }
}

val IMG_EXT = setOf("jpg", "jpeg", "png", "gif", "webp")
val TEXT_EXT = setOf("txt", "md", "json", "csv", "xml", "log", "html")

fun icon(f: File) = if (f.isDirectory) "📁" else when (f.extension.lowercase()) {
    in IMG_EXT, "heic", "bmp" -> "🖼️"
    "mp4", "mkv", "avi", "mov", "3gp", "webm" -> "🎬"
    "mp3", "wav", "m4a", "flac", "ogg", "opus" -> "🎵"
    "apk" -> "📦"
    "pdf", "doc", "docx", "txt", "md", "xls", "xlsx", "ppt", "pptx", "csv" -> "📄"
    "zip", "rar", "7z" -> "🗜️"
    else -> "📎"
}

@Composable fun OnResume(cb: () -> Unit) {
    val o = LocalLifecycleOwner.current
    DisposableEffect(o) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) cb() }
        o.lifecycle.addObserver(ob); onDispose { o.lifecycle.removeObserver(ob) }
    }
}

@Composable fun App() {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    OnResume { ok = Environment.isExternalStorageManager(); Session.roots = volumes(ctx).map { it.dir } }
    // Clear out Bin items older than 30 days, off the main thread.
    LaunchedEffect(ok) { if (ok) withContext(Dispatchers.IO) { purgeOldBin(volumes(ctx).map { it.dir }) } }
    var tab by rememberSaveable { mutableStateOf(0) }
    BackHandler(ok && tab != 0) { tab = 0 }
    val tabs = listOf("📁" to "Files", "🧹" to "Clean", "📱" to "Apps", "🗑️" to "Bin", "🎭" to "Stickers")
    Scaffold(bottomBar = {
        if (ok) NavigationBar {
            tabs.forEachIndexed { i, (e, t) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Text(e, style = MaterialTheme.typography.titleLarge) }, label = { Text(t, maxLines = 1) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (!ok) Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("Smart Files needs “All files access” to browse and clean your storage.")
                Spacer(Modifier.height(16.dp))
                Button({
                    val pkg = Uri.parse("package:${ctx.packageName}")
                    try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)) }
                    catch (e: Exception) { safeStart(ctx, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }) { Text("Grant access") }
            } else when (tab) { 0 -> FilesScreen(); 1 -> CleanScreen(); 2 -> AppsScreen(); 3 -> TrashScreen(); else -> StickersScreen() }
        }
    }
}

// ---------------- FILES ----------------
/** Everything the list needs, read once on a background thread so scrolling never touches the disk. */
data class FileEntry(val f: File, val dir: Boolean, val size: Long, val mod: Long, val kids: Int)

fun listEntries(dir: File, showHidden: Boolean): List<FileEntry> =
    (dir.listFiles() ?: emptyArray<File>())
        .filter { it.name != TRASH && (showHidden || !it.name.startsWith(".")) }
        .map { val d = it.isDirectory; FileEntry(it, d, if (d) 0L else it.length(), it.lastModified(), if (d) (it.list()?.size ?: 0) else 0) }

private val thumbs = android.util.LruCache<String, ImageBitmap>(300)

@Composable fun FileIcon(e: FileEntry) {
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (!e.dir && e.f.extension.lowercase() in IMG_EXT) {
            val key = e.f.path + "|" + e.mod
            val bmp by produceState<ImageBitmap?>(thumbs.get(key), key) {
                if (value == null) value = withContext(Dispatchers.IO) { loadBmp(e.f, 96)?.asImageBitmap() }?.also { thumbs.put(key, it) }
            }
            val b = bmp
            if (b != null) Image(b, null, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            else Text(icon(e.f), style = MaterialTheme.typography.headlineSmall)
        } else Text(icon(e.f), style = MaterialTheme.typography.headlineSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun FilesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dir by Session::dir
    var volTick by remember { mutableIntStateOf(0) }
    OnResume { volTick++ }
    val vols = remember(volTick) { volumes(ctx) }
    val root = vols.firstOrNull { dir.path == it.dir.path || dir.path.startsWith(it.dir.path + "/") }?.dir ?: Environment.getExternalStorageDirectory()
    var tick by remember { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var del by remember { mutableStateOf<File?>(null) }
    var ren by remember { mutableStateOf<File?>(null) }
    var edit by remember { mutableStateOf<File?>(null) }
    var mk by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<File>() }
    val df = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val free = remember(root, tick) { freeOf(root) }
    val shortcuts = remember(root) { listOf("Downloads" to "Download", "Camera" to "DCIM", "Pictures" to "Pictures", "Documents" to "Documents").filter { File(root, it.second).isDirectory } }

    fun go(d: File) { picked.clear(); query = ""; dir = d }
    fun toggle(f: File) { if (f in picked) picked.remove(f) else picked.add(f) }
    /** Runs a file operation off the main thread; the block returns a message to show, or null. */
    fun io(block: () -> String?) {
        scope.launch {
            busy = true
            val msg = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "Something went wrong" } }
            busy = false; msg?.let { toast(ctx, it) }; tick++
        }
    }

    BackHandler(dir != root) { go(dir.parentFile ?: root) }
    BackHandler(picked.isNotEmpty()) { picked.clear() }
    LaunchedEffect(dir, tick, showHidden) { entries = withContext(Dispatchers.IO) { listEntries(dir, showHidden) } }
    val items = remember(entries, sort, query) {
        val cmp: Comparator<FileEntry> = when (sort) {
            1 -> compareByDescending { it.size }
            2 -> compareByDescending { it.mod }
            else -> compareBy { it.f.name.lowercase() }
        }
        (entries ?: emptyList()).filter { it.f.name.contains(query, true) }
            .sortedWith(compareByDescending<FileEntry> { it.dir }.then(cmp).thenBy { it.f.name.lowercase() })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { go(dir.parentFile ?: root) }, enabled = dir != root) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f)) {
                Text((vols.firstOrNull { it.dir == root }?.name ?: "Storage") + dir.path.removePrefix(root.path), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(free, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vols.forEach { v -> FilterChip(selected = v.dir == root, onClick = { go(v.dir) }, label = { Text(v.name) }) }
            shortcuts.forEach { (n, p) -> AssistChip(onClick = { go(File(root, p)) }, label = { Text(n) }) }
            FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text("Hidden files") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("Search this folder") }, singleLine = true)
            TextButton({ sort = (sort + 1) % 3 }) { Text(listOf("Name", "Size", "Date")[sort]) }
            TextButton({ mk = true }) { Text("＋📁") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (Session.clip.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("📋 ${Session.clip.size} to ${if (Session.cut) "move" else "copy"}", Modifier.weight(1f))
            TextButton({ Session.clip.clear() }) { Text("✕") }
            Button({
                val files = Session.clip.toList(); val cut = Session.cut; val target = dir
                Session.clip.clear()
                io { val n = pasteInto(target, files, cut); "$n of ${files.size} done" }
            }) { Text("Paste here") }
        }
        if (picked.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${picked.size} selected", Modifier.weight(1f))
                TextButton({ picked.clear(); picked.addAll(items.map { it.f }) }) { Text("Select all") }
                TextButton({ picked.clear() }) { Text("✕") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ Session.clip.clear(); Session.clip.addAll(picked); Session.cut = false; picked.clear() }) { Text("Copy") }
                OutlinedButton({ Session.clip.clear(); Session.clip.addAll(picked); Session.cut = true; picked.clear() }) { Text("Move") }
                Button({ bulk = true }) { Text("Delete") }
            }
        }
        if (entries == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        else if (items.isEmpty()) Text(if (query.isEmpty()) "Empty folder" else "No matches", Modifier.padding(24.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.f.path }) { e ->
                val f = e.f
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().background(if (f in picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .combinedClickable(onLongClick = { toggle(f) }) {
                        if (picked.isNotEmpty()) toggle(f) else if (e.dir) go(f) else openFile(ctx, f)
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileIcon(e)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = if (e.dir) (if (e.kids == 1) "1 item" else "${e.kids} items") else fmt(e.size)
                        Text(sub + " · " + df.format(e.mod), style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        TextButton({ menu = true }) { Text("⋮") }
                        DropdownMenu(menu, { menu = false }) {
                            if (!e.dir) DropdownMenuItem({ Text("Open") }, { menu = false; openFile(ctx, f) })
                            if (!e.dir) DropdownMenuItem({ Text("Share") }, { menu = false; shareFile(ctx, f) })
                            if (!e.dir && e.size < 500_000 && f.extension.lowercase() in TEXT_EXT)
                                DropdownMenuItem({ Text("Edit text") }, { menu = false; edit = f })
                            DropdownMenuItem({ Text("Copy") }, { menu = false; Session.clip.clear(); Session.clip.add(f); Session.cut = false })
                            DropdownMenuItem({ Text("Move") }, { menu = false; Session.clip.clear(); Session.clip.add(f); Session.cut = true })
                            DropdownMenuItem({ Text("Rename") }, { menu = false; ren = f })
                            DropdownMenuItem({ Text("Delete") }, { menu = false; del = f })
                        }
                    }
                }
            }
        }
    }
    del?.let { f ->
        AlertDialog({ del = null }, title = { Text("Delete ${f.name}?") }, text = { Text("It moves to the Bin, where you can restore it for 30 days.") },
            confirmButton = { TextButton({ del = null; io { if (moveToTrash(f)) "Moved to Bin" else "Couldn’t delete. The file may be read-only." } }) { Text("Delete") } },
            dismissButton = { TextButton({ del = null }) { Text("Cancel") } })
    }
    if (mk) {
        var nm by remember { mutableStateOf("New folder") }
        AlertDialog({ mk = false }, title = { Text("New folder") }, text = { OutlinedTextField(nm, { nm = it }, singleLine = true) },
            confirmButton = { TextButton({ mk = false; val t = dir; val n = nm; io { makeFolder(t, n) } }) { Text("Create") } },
            dismissButton = { TextButton({ mk = false }) { Text("Cancel") } })
    }
    if (bulk) AlertDialog({ bulk = false }, title = { Text("Move ${picked.size} items to the Bin?") }, text = { Text("You can restore them for 30 days.") },
        confirmButton = {
            TextButton({
                val l = picked.toList(); picked.clear(); bulk = false
                io { val n = l.count { moveToTrash(it) }; "$n of ${l.size} moved to Bin" }
            }) { Text("Move to Bin") }
        },
        dismissButton = { TextButton({ bulk = false }) { Text("Cancel") } })
    ren?.let { f ->
        var name by remember { mutableStateOf(f.name) }
        AlertDialog({ ren = null }, title = { Text("Rename") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ val n = name; ren = null; io { renameFile(f, n) } }) { Text("Rename") } },
            dismissButton = { TextButton({ ren = null }) { Text("Cancel") } })
    }
    edit?.let { f ->
        val initial = remember(f) { runCatching { f.readText() }.getOrNull() }
        if (initial == null) LaunchedEffect(f) { toast(ctx, "Couldn’t read this file"); edit = null }
        else {
            var text by remember(f) { mutableStateOf(initial.orEmpty()) }
            AlertDialog({ edit = null }, title = { Text(f.name) },
                text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(max = 400.dp)) },
                confirmButton = {
                    TextButton({
                        val t = text; edit = null
                        io { if (runCatching { f.writeText(t) }.isSuccess) null else "Couldn’t save. The file may be read-only." }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton({ edit = null }) { Text("Cancel") } })
        }
    }
}

// ---------------- CLEAN ----------------
data class Junk(val f: File, val why: String, val safe: Boolean, val size: Long = f.length())

fun md5(f: File): String {
    val d = MessageDigest.getInstance("MD5"); val buf = ByteArray(65536)
    f.inputStream().use { s -> while (true) { val n = s.read(buf); if (n < 0) break; d.update(buf, 0, n) } }
    return d.digest().joinToString("") { "%02x".format(it) }
}

/** Cheap fingerprint (first and last 64 KB) so big videos are only fully hashed when they really look identical. */
fun quickKey(f: File): String = runCatching {
    RandomAccessFile(f, "r").use { r ->
        val len = r.length(); val buf = ByteArray(65536); val d = MessageDigest.getInstance("MD5")
        val a = r.read(buf); if (a > 0) d.update(buf, 0, a)
        if (len > 131072) { r.seek(len - 65536); val b = r.read(buf); if (b > 0) d.update(buf, 0, b) }
        d.digest().joinToString("") { "%02x".format(it) }
    }
}.getOrDefault(f.path)

fun scan(): List<Junk> {
    val out = ArrayList<Junk>(); val seen = HashSet<File>(); val sizes = HashMap<Long, MutableList<File>>()
    val now = System.currentTimeMillis()
    val temp = setOf("tmp", "temp", "log"); val review = setOf("bak", "old")
    fun add(f: File, why: String, safe: Boolean) { if (seen.add(f)) out.add(Junk(f, why, safe)) }
    Session.roots.asSequence().flatMap { r -> r.walkTopDown().onEnter { !it.path.contains("/Android/") && it.name != TRASH } }.filter { it.isFile }.forEach { f ->
        val e = f.extension.lowercase(); val len = f.length()
        when {
            e == "apk" -> add(f, "Installer APK (already installed?)", true)
            e in temp || f.path.contains("/.thumbnails/") -> add(f, "Temp / cache file", true)
            e in review -> add(f, "Backup or old file. Check before deleting", false)
            f.path.contains("/Download/") && now - f.lastModified() > 90L * 86_400_000 -> add(f, "Old download (90+ days)", false)
            len > 100_000_000 -> add(f, "Large file", false)
        }
        if (len > 1_000_000) sizes.getOrPut(len) { ArrayList() }.add(f)
    }
    for (g in sizes.values) {
        if (g.size < 2) continue
        for (maybe in g.groupBy { quickKey(it) }.values) {
            if (maybe.size < 2) continue
            for (d in maybe.groupBy { runCatching { md5(it) }.getOrDefault(it.path) }.values) {
                if (d.size < 2) continue
                val keep = d.minBy { it.lastModified() }
                d.filter { it != keep }.forEach { add(it, "Duplicate of ${keep.name}", false) }
            }
        }
    }
    return out.sortedByDescending { it.size }
}

@Composable fun CleanScreen() {
    val ctx = LocalContext.current
    val st = StatFs(Environment.getExternalStorageDirectory().path)
    val total = st.totalBytes; val free = st.availableBytes
    val junk = Session.junk
    val sel = Session.junkSel
    val busy = Session.scanning || Session.cleaning
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Storage", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { (total - free).toFloat() / total }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Text("${fmt(total - free)} used of ${fmt(total)} · ${fmt(free)} free")
        if (junk == null) BreakdownCard()
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { Session.startScan() }) {
                Text(if (Session.scanning) "Scanning…" else if (junk == null) "Scan for junk" else "Scan again")
            }
            OutlinedButton({ safeStart(ctx, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }) { Text("App caches") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Session.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
        junk?.let { list ->
            if (list.isEmpty()) Text("Nothing to clean 🎉")
            else Text("${list.size} items · ${fmt(list.sumOf { it.size })}. Installers and temp files are pre-selected. Review the rest before deleting.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.f.path }) { j ->
                    Row(Modifier.fillMaxWidth().clickable { if (j.f in sel) sel.remove(j.f) else sel.add(j.f) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(j.f in sel, { if (it) sel.add(j.f) else sel.remove(j.f) })
                        Column {
                            Text(j.f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${j.why} · ${fmt(j.size)}", style = MaterialTheme.typography.bodySmall)
                            Text((j.f.parent ?: "").removePrefix(Environment.getExternalStorageDirectory().path), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Button(enabled = sel.isNotEmpty() && !busy, onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete ${sel.size} selected (${fmt(list.filter { it.f in sel }.sumOf { it.size })})")
            }
        }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text("Delete ${sel.size} files?") },
        text = { Text("“To Bin” keeps them for 30 days, but the space is only freed when you empty the Bin. “Forever” frees the space now and can’t be undone.") },
        confirmButton = {
            Row {
                TextButton({ confirm = false; Session.cleanSelected(permanent = true) }) { Text("Forever") }
                TextButton({ confirm = false; Session.cleanSelected(permanent = false) }) { Text("To Bin") }
            }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

// ---------------- APPS ----------------
data class AppRow(val label: String, val pkg: String, val size: Long, val last: Long, val installed: Long)

fun usageOk(c: Context): Boolean {
    val a = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return a.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
}

fun loadApps(c: Context): List<AppRow> {
    val pm = c.packageManager; val now = System.currentTimeMillis()
    val stats = (c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).queryAndAggregateUsageStats(now - 90L * 86_400_000, now)
    return pm.getInstalledPackages(0).mapNotNull { p ->
        val a = p.applicationInfo ?: return@mapNotNull null
        if ((a.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || a.packageName == c.packageName || pm.getLaunchIntentForPackage(a.packageName) == null) null
        else AppRow(pm.getApplicationLabel(a).toString(), a.packageName, appSize(c, a), stats[a.packageName]?.lastTimeUsed ?: 0L, p.firstInstallTime)
    }.sortedWith(compareBy<AppRow> { maxOf(it.last, it.installed) }.thenByDescending { it.size })
}

@Composable fun AppsScreen() {
    val ctx = LocalContext.current
    var hasUsage by remember { mutableStateOf(usageOk(ctx)) }
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var first by remember { mutableStateOf(true) }
    OnResume { hasUsage = usageOk(ctx); if (first) { first = false } else { tick++ } }
    var bySize by rememberSaveable { mutableStateOf(false) }
    var q by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(tick, hasUsage) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    val now = System.currentTimeMillis(); val day = 86_400_000L
    // A new app that was never opened isn't "unused" yet: count from install time.
    fun stale(a: AppRow) = hasUsage && now - maxOf(a.last, a.installed) > 30 * day
    fun ago(t: Long): String = when (val d = (now - t) / day) { 0L -> "today"; 1L -> "yesterday"; else -> "$d days ago" }
    val shown = remember(apps, bySize, q) {
        val l = (apps ?: emptyList()).filter { it.label.contains(q, true) }
        if (bySize) l.sortedByDescending { it.size } else l
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (!hasUsage) {
            Text("Allow usage access so Smart Files can spot apps you never open.")
            Button({ safeStart(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access") }
        } else {
            val old = (apps ?: emptyList()).filter { stale(it) }
            Text("${old.size} apps unused for 30+ days · ${fmt(old.sumOf { it.size })} to reclaim", style = MaterialTheme.typography.titleSmall)
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), placeholder = { Text("Search apps") }, singleLine = true)
        if (apps == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            item { TextButton({ bySize = !bySize }) { Text(if (bySize) "Sorted by size · tap for unused-first" else "Sorted unused-first · tap for size") } }
            items(shown, key = { it.pkg }) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val use = when {
                            !hasUsage -> "usage unknown"
                            a.last == 0L && now - a.installed < 30 * day -> "installed ${ago(a.installed)}, not opened yet"
                            a.last == 0L -> "not used in 90+ days"
                            else -> "last used ${ago(a.last)}"
                        }
                        Text("${fmt(a.size)} · $use", style = MaterialTheme.typography.bodySmall,
                            color = if (stale(a)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ safeStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) }) { Text("Info") }
                    TextButton({ safeStart(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) }) { Text("Uninstall") }
                }
            }
        }
    }
}
