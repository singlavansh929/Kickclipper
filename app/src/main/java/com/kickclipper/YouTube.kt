package com.kickclipper

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo

class NpDownloader : Downloader() {
    private val client = OkHttpClient()
    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val b = okhttp3.Request.Builder().url(request.url()).method(method, body)
        request.headers().forEach { (k, vs) -> b.removeHeader(k); vs.forEach { b.addHeader(k, it) } }
        client.newCall(b.build()).execute().use { r ->
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

object YouTube {
    private var ready = false

    /** Returns a direct URL of the best muxed (video+audio) stream, up to 720p. */
    fun streamUrl(link: String): String {
        if (!ready) { NewPipe.init(NpDownloader()); ready = true }
        val info = StreamInfo.getInfo(link)
        val best = info.videoStreams.filter { it.height <= 720 }.maxByOrNull { it.height }
            ?: info.videoStreams.maxByOrNull { it.height }
            ?: error("No downloadable stream found for this video.")
        return best.content
    }
}
