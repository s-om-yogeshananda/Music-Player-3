package com.yourname.musicplayer.youtube

import com.yourname.musicplayer.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

class NpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody(null)
            method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val b = okhttp3.Request.Builder().url(request.url()).method(method, body)
        b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0")
        request.headers().forEach { (k, vs) ->
            b.removeHeader(k)
            vs.forEach { v -> b.addHeader(k, v) }
        }
        client.newCall(b.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("reCaptcha required", request.url())
            return Response(resp.code, resp.message, resp.headers.toMultimap(), resp.body?.string(), resp.request.url.toString())
        }
    }
}

data class YtResult(val id: String, val title: String, val uploader: String, val thumb: String?, val url: String, val durationSec: Long)

object YouTube {
    @Volatile private var inited = false

    private fun init(c: AppContainer) {
        if (!inited) {
            NewPipe.init(NpDownloader(c.http))
            inited = true
        }
    }

    suspend fun search(c: AppContainer, query: String): List<YtResult> = withContext(Dispatchers.IO) {
        init(c)
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        ex.fetchPage()
        ex.initialPage.items.filterIsInstance<StreamInfoItem>().map { it ->
            YtResult(
                id = it.url.substringAfter("v=").substringBefore("&"),
                title = it.name ?: "",
                uploader = it.uploaderName ?: "",
                thumb = it.thumbnails.firstOrNull()?.url,
                url = it.url,
                durationSec = it.duration
            )
        }
    }

    suspend fun streamUrl(c: AppContainer, videoUrl: String): String = withContext(Dispatchers.IO) {
        init(c)
        val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        val best = info.audioStreams.maxByOrNull { it.averageBitrate }
        best?.content ?: throw IllegalStateException("No audio stream found")
    }
}
