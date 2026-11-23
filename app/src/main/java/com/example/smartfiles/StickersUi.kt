package com.example.smartfiles

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

@Composable fun StickerCell(f: File, on: Boolean, click: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, f) { value = withContext(Dispatchers.IO) { loadBmp(f, 256)?.asImageBitmap() } }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        .clickable(onClick = click).padding(6.dp), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize()) }
        if (on) Text("✓", Modifier.align(Alignment.TopEnd), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable fun StickersScreen() {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<File>?>(null) }
    var packs by remember { mutableStateOf(listOf<File>()) }
    var tick by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("My Stickers") }
    var busy by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<File>() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("validation_error")?.let { toast(ctx, "WhatsApp: $it") }
    }
    val apps = remember { listOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "Business").filter { runCatching { ctx.packageManager.getPackageInfo(it.first, 0) }.isSuccess } }
    LaunchedEffect(tick) { val r = withContext(Dispatchers.IO) { findStickers() to myPacks(ctx) }; found = r.first; packs = r.second }
    fun addTo(pkg: String, id: String, n: String) {
        try {
            launcher.launch(Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").setPackage(pkg).putExtra("sticker_pack_id", id)
                .putExtra("sticker_pack_authority", ctx.packageName + ".stickercontentprovider").putExtra("sticker_pack_name", n))
        } catch (e: Exception) { toast(ctx, "Update WhatsApp to the latest version") }
    }
    val list = found ?: emptyList()
    LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.fillMaxSize().padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("WhatsApp stickers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(if (found == null) "Searching WhatsApp folders…" else "${list.size} stickers found. Tap 3–30 to select, then add them back to WhatsApp as a pack.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(vertical = 6.dp), label = { Text("Pack name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (apps.isEmpty()) Text("WhatsApp isn’t installed.")
                    apps.forEach { (pkg, label) ->
                        Button(enabled = sel.size in 3..30 && !busy, onClick = {
                            busy = true
                            val title = name.trim().ifEmpty { "My Stickers" }; val picks = sel.toList()
                            scope.launch {
                                val id = withContext(Dispatchers.IO) { buildPack(ctx, title, picks) }
                                busy = false
                                if (id == null) toast(ctx, "Need at least 3 usable stickers of the same kind (all static or all animated)")
                                else { sel.clear(); tick++; addTo(pkg, id, title) }
                            }
                        }) { Text(if (busy) "Preparing…" else "Add to $label") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Selected ${sel.size}/30", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ sel.clear(); sel.addAll(list.take(30)) }) { Text("First 30") }
                    TextButton({ sel.clear() }) { Text("Clear") }
                }
                if (packs.isNotEmpty()) Text("My sticker packs", style = MaterialTheme.typography.titleSmall)
                packs.forEach { d ->
                    val pn = File(d, "name.txt").readText()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🗂️ $pn", Modifier.weight(1f), maxLines = 1)
                        apps.forEach { (pkg, label) -> TextButton({ addTo(pkg, d.name, pn) }) { Text("Re-add · $label") } }
                        TextButton({ d.deleteRecursively(); tick++ }) { Text("✕") }
                    }
                }
                if (found != null && list.isEmpty()) Text("No stickers found. WhatsApp only keeps stickers you received or sent on this phone.", Modifier.padding(vertical = 12.dp))
            }
        }
        items(list, key = { it.path }) { f -> StickerCell(f, f in sel) { if (f in sel) sel.remove(f) else if (sel.size < 30) sel.add(f) } }
    }
}
