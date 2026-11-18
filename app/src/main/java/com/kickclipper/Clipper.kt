@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kickclipper

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.Effect
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

object Clipper {
    private val http = OkHttpClient()
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept", "application/json, */*").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}. Kick may be blocking this request; paste a direct .m3u8 link instead.")
            return r.body!!.string()
        }
    }

    // ---- 1. Kick link -> HLS playlist URL ----
    private fun resolvePlaylist(input: String): String {
        val u = input.trim()
        if (u.contains(".m3u8")) return u
        Regex("(?:videos?/|video=)([0-9a-fA-F-]{36})").find(u)?.let {
            return JSONObject(get("https://kick.com/api/v1/video/${it.groupValues[1]}")).getString("source")
        }
        val ch = Regex("kick\\.com/([A-Za-z0-9_-]+)").find(u) ?: error("That doesn't look like a Kick link.")
        val pb = JSONObject(get("https://kick.com/api/v2/channels/${ch.groupValues[1]}")).optString("playback_url")
        if (pb.isEmpty()) error("Channel isn't live. Paste a VOD link instead.")
        return pb
    }

    private data class Seg(val url: String, val dur: Double)

    private fun pickVariant(base: String, text: String): String {
        if (!text.contains("#EXT-X-STREAM-INF")) return base
        val lines = text.lines().map { it.trim() }
        val vars = mutableListOf<Pair<Long, String>>()
        for (i in lines.indices) if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
            val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLong() ?: 0L
            val next = lines.drop(i + 1).first { it.isNotEmpty() && !it.startsWith("#") }
            vars += bw to URI(base).resolve(next).toString()
        }
        val ok = vars.filter { it.first <= 4_000_000 }.maxByOrNull { it.first }
        return (ok ?: vars.minByOrNull { it.first }!!).second
    }

    private fun parseSegs(base: String, text: String): List<Seg> {
        val segs = mutableListOf<Seg>(); var dur = 0.0
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF:")) dur = l.substring(8).substringBefore(",").toDoubleOrNull() ?: 0.0
            else if (l.isNotEmpty() && !l.startsWith("#")) segs += Seg(URI(base).resolve(l).toString(), dur)
        }
        return segs
    }

    // ---- 2. Download the chosen time range into one .ts file ----
    private fun download(playlistUrl: String, startS: Double, endS: Double, out: File, log: (String) -> Unit) {
        var base = playlistUrl
        var text = get(base)
        val variant = pickVariant(base, text)
        if (variant != base) { base = variant; text = get(base) }
        val segs = parseSegs(base, text)
        var t = 0.0
        val chosen = segs.filter { val s = t; t += it.dur; t > startS && s < endS }
        if (chosen.isEmpty()) error("No video found in that time range.")
        log("Downloading ${chosen.size} segments (~${(chosen.sumOf { it.dur } / 60).toInt()} min)...")
        out.outputStream().use { o ->
            chosen.forEach { seg ->
                http.newCall(Request.Builder().url(seg.url).header("User-Agent", UA).build()).execute().use { r ->
                    if (!r.isSuccessful) error("Segment download failed (HTTP ${r.code})")
                    r.body!!.byteStream().copyTo(o)
                }
            }
        }
    }

    private fun downloadFile(url: String, out: File, log: (String) -> Unit) {
        log("Downloading video...")
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) error("Download failed (HTTP ${r.code})")
            out.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
        }
    }

    // ---- 3. Loudness (RMS) per second ----
    private fun loudness(path: String): DoubleArray {
        val ex = MediaExtractor(); ex.setDataSource(path)
        var ti = -1; var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ti = i; fmt = f; break }
        }
        if (ti < 0 || fmt == null) { ex.release(); return DoubleArray(0) }
        ex.selectTrack(ti)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val sums = HashMap<Int, Double>(); val cnts = HashMap<Int, Long>()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi >= 0) {
                val sb = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                val sec = (info.presentationTimeUs / 1_000_000).toInt()
                var s = 0.0; val n = sb.remaining()
                for (k in 0 until n) { val v = sb.get(k).toDouble(); s += v * v }
                sums[sec] = (sums[sec] ?: 0.0) + s
                cnts[sec] = (cnts[sec] ?: 0L) + n
                codec.releaseOutputBuffer(oi, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        val size = (sums.keys.maxOrNull() ?: -1) + 1
        return DoubleArray(size) { val c = cnts[it] ?: 0L; if (c > 0) sqrt(sums[it]!! / c) else 0.0 }
    }

    private fun pickPeaks(l: DoubleArray, count: Int, clipSec: Int): List<Int> {
        val sm = DoubleArray(l.size) { i ->
            var s = 0.0; var c = 0
            for (j in maxOf(0, i - 2)..minOf(l.size - 1, i + 2)) { s += l[j]; c++ }
            s / c
        }
        val chosen = mutableListOf<Int>()
        for (i in sm.indices.sortedByDescending { sm[it] }) {
            if (chosen.size >= count) break
            if (chosen.none { abs(it - i) < clipSec }) chosen += i
        }
        return chosen.sorted()
    }

    private fun videoSize(path: String): Pair<Int, Int> {
        val ex = MediaExtractor(); ex.setDataSource(path)
        try {
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true)
                    return f.getInteger(MediaFormat.KEY_WIDTH) to f.getInteger(MediaFormat.KEY_HEIGHT)
            }
        } finally { ex.release() }
        return 1920 to 1080
    }

    // ---- 4. Trim + center-crop to 9:16 (1080x1920) ----
    private suspend fun export(ctx: Context, src: File, startS: Double, endS: Double, out: File, w: Int, h: Int) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val clip = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((startS * 1000).toLong())
                    .setEndPositionMs((endS * 1000).toLong()).build()
                val item = MediaItem.Builder().setUri(Uri.fromFile(src)).setClippingConfiguration(clip).build()
                val frac = (9f / 16f) / (w.toFloat() / h)   // visible width fraction
                val video = mutableListOf<Effect>()
                if (frac < 1f) video += Crop(-frac, frac, -1f, 1f)
                video += Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT)
                val edited = EditedMediaItem.Builder(item)
                    .setEffects(Effects(emptyList<AudioProcessor>(), video)).build()
                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, e: ExportException) {
                            if (cont.isActive) cont.resumeWith(Result.failure(e))
                        }
                    }).build()
                transformer.start(edited, out.absolutePath)
                cont.invokeOnCancellation { transformer.cancel() }
            }
        }

    private fun saveToGallery(ctx: Context, f: File, name: String) {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/KickClips")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
    }

    suspend fun run(
        ctx: Context, input: String, startMin: Int, scanMin: Int, count: Int, clipSec: Int,
        log: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }
        log("Finding stream...")
        val isYt = Regex("youtube\\.com|youtu\\.be").containsMatchIn(input)
        val playlist = if (isYt) "" else resolvePlaylist(input)
        val raw = File(dir, if (isYt) "raw.mp4" else "raw.ts")
        if (isYt) downloadFile(YouTube.streamUrl(input.trim()), raw, log)
        else download(playlist, startMin * 60.0, (startMin + scanMin) * 60.0, raw, log)
        log("Finding the loudest moments...")
        val loud = loudness(raw.absolutePath)
        if (loud.isEmpty()) error("No audio track found.")
        if (isYt) for (i in loud.indices) if (i < startMin * 60 || i >= (startMin + scanMin) * 60) loud[i] = 0.0
        val (w, h) = videoSize(raw.absolutePath)
        val peaks = pickPeaks(loud, count, clipSec)
        val stamp = System.currentTimeMillis()
        peaks.forEachIndexed { i, peak ->
            val s = maxOf(0.0, peak - clipSec * 0.4)
            val e = minOf(loud.size.toDouble(), s + clipSec)
            log("Clip ${i + 1}/${peaks.size}: ${s.toInt()}s-${e.toInt()}s (in scanned range)")
            val out = File(dir, "clip$i.mp4")
            export(ctx, raw, s, e, out, w, h)
            saveToGallery(ctx, out, "kick_clip_${stamp}_${i + 1}.mp4")
        }
        dir.deleteRecursively()
        log("Done. ${peaks.size} clips saved in Movies/KickClips.")
    }
}
