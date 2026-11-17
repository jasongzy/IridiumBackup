package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.network.io.CountingOutputStreamImpl
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import com.xayah.libsardine.DavResource
import com.xayah.libsardine.impl.OkHttpSardine
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebDAVClientImpl(private val entity: CloudEntity, private val extra: WebDAVExtra) : CloudClient {
    private var client: OkHttpSardine? = null
    private var uploadProgress: ((Long, Long) -> Unit)? = null

    private fun log(msg: () -> String) = LogUtil.log("WebDAVClientImpl", msg())

    private fun getPath(path: String): String {
        val url = entity.host.toHttpUrl().newBuilder()
        for (segment in path.split('/').filter(String::isNotEmpty)) {
            require(segment != "." && segment != "..") { "Invalid WebDAV path: $path" }
            url.addPathSegment(segment)
        }
        return url.build().toString()
    }

    private fun <T> withClient(block: (OkHttpSardine) -> T): T = block(checkNotNull(client))

    override fun connect() {
        val origin = entity.host.toHttpUrl()
        val authorization = Credentials.basic(entity.user, entity.pass)
        var authenticationRequested = false
        fun isAccountOrigin(url: HttpUrl) = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(IO_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(IO_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .authenticator { _, response ->
                val request = response.request
                if (!isAccountOrigin(request.url) || request.header("Authorization") != null) null
                else {
                    authenticationRequested = true
                    request.newBuilder().header("Authorization", authorization).build()
                }
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val method = chain.call().request().method
                if (request.method != method) {
                    throw ProtocolException("WebDAV redirect changed $method to ${request.method}")
                }
                chain.proceed(request)
            }
            .addInterceptor { chain ->
                var request = chain.request()
                if (authenticationRequested && isAccountOrigin(request.url)) {
                    request = request.newBuilder().header("Authorization", authorization).build()
                }
                val requestBody = request.body
                val onUpload = uploadProgress
                if (request.method == "PUT" && requestBody != null && onUpload != null) {
                    request = request.newBuilder().method("PUT", object : RequestBody() {
                        override fun contentType() = requestBody.contentType()
                        override fun contentLength() = requestBody.contentLength()

                        override fun writeTo(sink: BufferedSink) {
                            val total = contentLength()
                            var written = 0L
                            onUpload(0, total)
                            val counting = object : ForwardingSink(sink) {
                                override fun write(source: Buffer, byteCount: Long) {
                                    super.write(source, byteCount)
                                    written += byteCount
                                    onUpload(written, total)
                                }
                            }.buffer()
                            requestBody.writeTo(counting)
                            counting.flush()
                        }
                    }).build()
                }
                checkCollectionResult(chain.proceed(request))
            }
        if (extra.insecure) {
            try {
                val trustAllCerts = arrayOf<TrustManager>(
                    object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                )

                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())

                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            } catch (_: Exception) {
                // do nothing
            }
        }

        client = OkHttpSardine(builder.build()).apply {
            list(entity.host)
        }
    }

    override fun disconnect() {
        client = null
    }

    private fun checkCollectionResult(response: Response): Response {
        val method = response.request.method
        if (response.code != 207 || (method != "DELETE" && method != "MOVE")) return response
        return response.use {
            try {
                val xml = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
                xml.setInput(it.body!!.byteStream(), null)
                xml.nextTag()
                xml.require(XmlPullParser.START_TAG, "DAV:", "multistatus")
                var href = ""
                var code: Int? = null
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.namespace != "DAV:") continue
                    if (xml.eventType == XmlPullParser.START_TAG && xml.depth == 2 && xml.name == "response") {
                        href = ""
                        code = null
                    } else if (xml.eventType == XmlPullParser.START_TAG && xml.depth == 3) {
                        when (xml.name) {
                            "href" -> href = xml.nextText()
                            "status" -> code = xml.nextText().substringAfter(' ', "").substringBefore(' ').toIntOrNull()
                        }
                    } else if (xml.eventType == XmlPullParser.END_TAG && xml.depth == 2 && xml.name == "response") {
                        if (code == null || code !in 200..299) {
                            val path = it.request.url.resolve(href)?.encodedPath ?: it.request.url.encodedPath
                            throw IOException("WebDAV $method failed: $path (${code ?: "invalid status"})")
                        }
                    }
                }
            } catch (e: XmlPullParserException) {
                throw IOException("Invalid WebDAV $method response", e)
            }
            it.newBuilder().body("".toResponseBody()).build()
        }
    }

    override fun mkdir(dst: String) = withClient { client ->
        log { "mkdir: $dst" }
        client.createDirectory(getPath(dst))
    }

    override fun mkdirRecursively(dst: String) = withClient { client ->
        val dirs = dst.split("/")
        var currentDir = ""
        for (i in dirs) {
            currentDir += "/$i"
            currentDir = currentDir.trimStart('/')
            if (client.exists(getPath(currentDir)).not()) mkdir(currentDir)
        }
    }

    override fun renameTo(src: String, dst: String, replace: Boolean) = withClient { client ->
        log { "renameTo: from $src to $dst" }
        client.move(getPath(src), getPath(dst), replace)
    }

    override fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit) = withClient { client ->
        val name = PathUtil.getFileName(src)
        val dstPath = getPath("$dst/$name")
        log { "upload: $src to $dst/$name" }
        val srcFile = File(src)
        uploadProgress = onUploading
        try {
            client.put(dstPath, srcFile, null)
            onUploading(srcFile.length(), srcFile.length())
        } finally {
            uploadProgress = null
        }
    }

    override fun read(src: String, output: OutputStream, onDownloading: (written: Long, total: Long) -> Unit) = withClient { client ->
        val bytes = client.get(getPath(src)).use { input ->
            CountingOutputStreamImpl(output, -1, onDownloading).use { output ->
                input.copyTo(output)
            }
        }
        onDownloading(bytes, bytes)
    }

    override fun deleteFile(src: String) = withClient { client ->
        log { "deleteFile: $src" }
        client.delete(getPath(src))
    }

    // WebDAV DELETE always removes the entire collection.
    override fun removeEmptyDirectory(src: String) = Unit

    override fun deleteRecursively(src: String) = deleteFile(src)

    private fun listResources(src: String): Pair<DavResource, List<DavResource>> {
        val url = getPath(src)
        val path = url.toHttpUrl().toUri().path.trimEnd('/')
        val resources = withClient { it.list(url) }
        val self = resources.firstOrNull { it.path.trimEnd('/') == path } ?: throw IOException("Incomplete WebDAV listing: $src")
        val children = resources.filter {
            it !== self && it.path.trimEnd('/').substringBeforeLast('/', "") == path && it.name.isNotEmpty() && it.name != "." && it.name != ".."
        }
        return self to children
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()
        for (resource in listResources(src).second) {
            val item = FileParcelable(resource.name, resource.creation?.time ?: 0L)
            if (resource.isDirectory) directories.add(item) else files.add(item)
        }
        files.sortBy { it.name }
        directories.sortBy { it.name }
        return DirChildrenParcelable(files = files, directories = directories)
    }

    override fun walkFileTree(src: String): List<PathParcelable> = buildList {
        fun walk(path: String) {
            val (self, children) = listResources(path)
            if (!self.isDirectory) add(PathParcelable(path))
            for (child in children) {
                val entry = "${path.trimEnd('/')}/${child.name}"
                if (child.isDirectory) walk(entry) else add(PathParcelable(entry))
            }
        }
        walk(src)
    }

    override fun exists(src: String): Boolean = withClient { it.exists(getPath(src)) }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val remote = pickDirectory(context, remotePath = { it.ifEmpty { "/" } })
        onSet(remote, entity.extra)
    }
}
