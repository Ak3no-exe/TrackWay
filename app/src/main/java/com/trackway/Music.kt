package com.trackway

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

data class Track(val title: String, val artist: String, val res: Int)

object MusicPlayer {
    val tracks = listOf(
        Track("700 – M R H", "BORO", R.raw.boro_700),
        Track("Le Plus Beau", "TeddyBear", R.raw.teddybear_le_plus_beau),
        Track("Suce ton shab", "", R.raw.suce_ton_shab),
        Track("Onlyfan", "HEM", R.raw.hem_onlyfan)
    )
    var index by mutableIntStateOf(-1)
    var playing by mutableStateOf(false)
    var pos by mutableIntStateOf(0)
    var dur by mutableIntStateOf(0)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(0) // 0 off, 1 tout, 2 un titre
    private var mp: MediaPlayer? = null
    private var appCtx: Context? = null

    fun play(ctx: Context, i: Int) {
        appCtx = ctx.applicationContext
        mp?.release()
        val m = MediaPlayer.create(appCtx, tracks[i].res) ?: return
        m.setOnCompletionListener { onEnd() }
        m.start()
        mp = m; index = i; playing = true; dur = m.duration; pos = 0
    }

    private fun onEnd() {
        val c = appCtx ?: return
        if (repeat == 2) play(c, index) else next(c, auto = true)
    }

    fun toggle(ctx: Context) {
        appCtx = ctx.applicationContext
        val m = mp
        if (m == null) { play(ctx, 0); return }
        if (m.isPlaying) { m.pause(); playing = false } else { m.start(); playing = true }
    }

    fun next(ctx: Context, auto: Boolean = false) {
        val n = tracks.size
        val ni = if (shuffle && n > 1) (0 until n).filter { it != index }.random() else index + 1
        if (ni >= n) {
            if (repeat == 1 || !auto) play(ctx, 0)
            else { mp?.pause(); mp?.seekTo(0); playing = false; pos = 0 }
        } else play(ctx, ni)
    }

    fun prev(ctx: Context) {
        val m = mp
        if (m != null && m.currentPosition > 3000) { m.seekTo(0); pos = 0 }
        else play(ctx, if (index <= 0) tracks.size - 1 else index - 1)
    }

    fun seek(ms: Int) { mp?.seekTo(ms); pos = ms }
    fun tick() { val m = mp ?: return; if (playing) pos = m.currentPosition }
}

private fun mmss(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun MusicTab() {
    val ctx = LocalContext.current
    val mp = MusicPlayer
    LaunchedEffect(Unit) { while (true) { mp.tick(); delay(500) } }
    val cur = mp.tracks.getOrNull(mp.index)
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Musique", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎵", fontSize = 48.sp)
                Text(cur?.title ?: "Choisis un titre", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (cur != null && cur.artist.isNotEmpty()) Text(cur.artist)
                Slider(value = if (mp.dur > 0) mp.pos.toFloat() / mp.dur else 0f,
                    onValueChange = { if (mp.dur > 0) mp.seek((it * mp.dur).toInt()) })
                Row(Modifier.fillMaxWidth()) {
                    Text(mmss(mp.pos), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(mmss(mp.dur), style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { mp.shuffle = !mp.shuffle }) { Text("🔀", fontSize = 24.sp, color = if (mp.shuffle) on else off) }
                    IconButton(onClick = { mp.prev(ctx) }) { Text("⏮", fontSize = 28.sp) }
                    Button(onClick = { mp.toggle(ctx) }) { Text(if (mp.playing) "⏸" else "▶", fontSize = 28.sp) }
                    IconButton(onClick = { mp.next(ctx) }) { Text("⏭", fontSize = 28.sp) }
                    IconButton(onClick = { mp.repeat = (mp.repeat + 1) % 3 }) {
                        Text(if (mp.repeat == 2) "🔂" else "🔁", fontSize = 24.sp, color = if (mp.repeat == 0) off else on)
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(mp.tracks) { i, t ->
                Row(Modifier.fillMaxWidth().clickable { mp.play(ctx, i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (i == mp.index && mp.playing) "🔊" else "🎵", Modifier.width(36.dp))
                    Column {
                        Text(t.title, color = if (i == mp.index) on else Color.Unspecified)
                        if (t.artist.isNotEmpty()) Text(t.artist, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
