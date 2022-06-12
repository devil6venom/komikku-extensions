package eu.kanade.tachiyomi.extension.all.bilibilicomics

import eu.kanade.tachiyomi.multisrc.bilibili.Bilibili
import eu.kanade.tachiyomi.multisrc.bilibili.BilibiliAccessToken
import eu.kanade.tachiyomi.multisrc.bilibili.BilibiliAccessTokenCookie
import eu.kanade.tachiyomi.multisrc.bilibili.BilibiliComicDto
import eu.kanade.tachiyomi.multisrc.bilibili.BilibiliCredential
import eu.kanade.tachiyomi.multisrc.bilibili.BilibiliGetCredential
import eu.kanade.tachiyomi.multisrc.bilibili.BilibiliIntl
import eu.kanade.tachiyomi.multisrc.bilibili.BilibiliTag
import eu.kanade.tachiyomi.multisrc.bilibili.BilibiliUnlockedEpisode
import eu.kanade.tachiyomi.multisrc.bilibili.BilibiliUserEpisodes
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.URLDecoder

class BilibiliComicsFactory : SourceFactory {
    override fun createSources() = listOf(
        BilibiliComicsEn(),
        BilibiliComicsCn(),
        BilibiliComicsId(),
        BilibiliComicsEs()
    )
}

abstract class BilibiliComics(lang: String) : Bilibili(
    "BILIBILI COMICS",
    "https://www.bilibilicomics.com",
    lang
) {

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor(::signedInIntercept)
        .addInterceptor(::expiredTokenIntercept)
        .rateLimitHost(baseUrl.toHttpUrl(), 1)
        .rateLimitHost(CDN_URL.toHttpUrl(), 2)
        .rateLimitHost(COVER_CDN_URL.toHttpUrl(), 2)
        .build()

    override val signedIn: Boolean
        get() = accessTokenCookie != null

    private val globalApiSubDomain: String
        get() = GLOBAL_API_SUBDOMAINS[(accessTokenCookie?.area?.toIntOrNull() ?: 1) - 1]

    private val globalApiBaseUrl: String
        get() = "https://$globalApiSubDomain.bilibilicomics.com"

    private var accessTokenCookie: BilibiliAccessTokenCookie? = null

    override fun chapterListParse(response: Response): List<SChapter> {
        if (!signedIn) {
            return super.chapterListParse(response)
        }

        val result = response.parseAs<BilibiliComicDto>()

        if (result.code != 0) {
            return emptyList()
        }

        val comic = result.data!!

        val userEpisodesRequest = userEpisodesRequest(comic.id)
        val userEpisodesResponse = client.newCall(userEpisodesRequest).execute()
        val unlockedEpisodes = userEpisodesParse(userEpisodesResponse)

        return comic.episodeList
            .filter { episode ->
                (episode.payMode == 0 && episode.payGold == 0) ||
                    episode.id in unlockedEpisodes
            }
            .map { ep -> chapterFromObject(ep, comic.id) }
    }

    private fun userEpisodesRequest(comicId: Int): Request {
        val jsonPayload = buildJsonObject { put("comic_id", comicId) }
        val requestBody = jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .set("Referer", baseUrl)
            .build()

        val apiUrl = "$globalApiBaseUrl/$GLOBAL_BASE_API_COMIC_ENDPOINT/GetUserEpisodes".toHttpUrl()
            .newBuilder()
            .addCommonParameters()
            .toString()

        return POST(apiUrl, newHeaders, requestBody)
    }

    private fun userEpisodesParse(response: Response): List<Int> {
        if (!response.isSuccessful) {
            return emptyList()
        }

        val result = response.parseAs<BilibiliUserEpisodes>()

        if (result.code != 0) {
            return emptyList()
        }

        return result.data!!.unlockedEpisodes.orEmpty()
            .map(BilibiliUnlockedEpisode::id)
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (!signedIn) {
            return super.pageListRequest(chapter)
        }

        val chapterPaths = (baseUrl + chapter.url).toHttpUrl().pathSegments
        val comicId = chapterPaths[0].removePrefix("mc").toInt()
        val episodeId = chapterPaths[1].toInt()

        val jsonPayload = BilibiliGetCredential(comicId, episodeId, 1)
        val requestBody = json.encodeToString(jsonPayload).toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .set("Referer", baseUrl + chapter.url)
            .build()

        val apiUrl = "$globalApiBaseUrl/$GLOBAL_BASE_API_USER_ENDPOINT/GetCredential".toHttpUrl()
            .newBuilder()
            .addCommonParameters()
            .toString()

        return POST(apiUrl, newHeaders, requestBody)
    }

    override fun pageListParse(response: Response): List<Page> {
        if (!signedIn) {
            return super.pageListParse(response)
        }

        if (!response.isSuccessful) {
            throw Exception(intl.failedToGetCredential)
        }

        val result = response.parseAs<BilibiliCredential>()
        val credential = result.data?.credential ?: ""

        val requestPayload = response.request.bodyString
        val credentialInfo = json.decodeFromString<BilibiliGetCredential>(requestPayload)
        val chapterUrl = "/mc${credentialInfo.comicId}/${credentialInfo.episodeId}"

        val imageIndexRequest = imageIndexRequest(chapterUrl, credential)
        val imageIndexResponse = client.newCall(imageIndexRequest).execute()

        return super.pageListParse(imageIndexResponse)
    }

    private fun signedInIntercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val requestUrl = request.url.toString()

        if (!requestUrl.contains("bilibilicomics.com")) {
            return chain.proceed(request)
        }

        val authCookie = client.cookieJar.loadForRequest(request.url)
            .firstOrNull { cookie -> cookie.name == ACCESS_TOKEN_COOKIE_NAME }
            ?.let { cookie -> URLDecoder.decode(cookie.value, "UTF-8") }
            ?.let { jsonString -> json.decodeFromString<BilibiliAccessTokenCookie>(jsonString) }

        if (accessTokenCookie == null) {
            accessTokenCookie = authCookie
        } else if (authCookie == null) {
            accessTokenCookie = null
        }

        if (!accessTokenCookie?.accessToken.isNullOrEmpty()) {
            request = request.newBuilder()
                .addHeader("Authorization", "Bearer ${accessTokenCookie!!.accessToken}")
                .build()
        }

        val response = chain.proceed(request)

        // Try to refresh the token if it expired.
        if (response.code == 401 && !accessTokenCookie?.refreshToken.isNullOrEmpty()) {
            response.close()

            val refreshTokenRequest = refreshTokenRequest(
                accessTokenCookie!!.accessToken,
                accessTokenCookie!!.refreshToken
            )
            val refreshTokenResponse = chain.proceed(refreshTokenRequest)

            accessTokenCookie = refreshTokenParse(refreshTokenResponse)

            request = request.newBuilder()
                .header("Authorization", "Bearer ${accessTokenCookie!!.accessToken}")
                .build()
            return chain.proceed(request)
        }

        return response
    }

    private fun refreshTokenRequest(accessToken: String, refreshToken: String): Request {
        val jsonPayload = buildJsonObject { put("refresh_token", refreshToken) }
        val requestBody = jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .add("Authorization", "Bearer $accessToken")
            .set("Referer", baseUrl)
            .build()

        val apiUrl = "$globalApiBaseUrl/$GLOBAL_BASE_API_USER_ENDPOINT/RefreshToken".toHttpUrl()
            .newBuilder()
            .addCommonParameters()
            .toString()

        return POST(apiUrl, newHeaders, requestBody)
    }

    private fun refreshTokenParse(response: Response): BilibiliAccessTokenCookie {
        if (!response.isSuccessful) {
            throw IOException(intl.failedToRefreshToken)
        }

        val result = response.parseAs<BilibiliAccessToken>()

        if (result.code != 0) {
            throw IOException(intl.failedToRefreshToken)
        }

        val accessToken = result.data!!

        return BilibiliAccessTokenCookie(
            accessToken.accessToken,
            accessToken.refreshToken,
            accessTokenCookie!!.area
        )
    }

    private val Request.bodyString: String
        get() {
            val requestCopy = newBuilder().build()
            val buffer = Buffer()

            return runCatching { buffer.apply { requestCopy.body!!.writeTo(this) }.readUtf8() }
                .getOrNull() ?: ""
        }

    companion object {
        private const val ACCESS_TOKEN_COOKIE_NAME = "access_token"

        private val GLOBAL_API_SUBDOMAINS = arrayOf("us-user", "sg-user")
        private const val GLOBAL_BASE_API_USER_ENDPOINT = "twirp/global.v1.User"
        private const val GLOBAL_BASE_API_COMIC_ENDPOINT = "twirp/comic.v1.User"
    }
}

class BilibiliComicsEn : BilibiliComics(BilibiliIntl.ENGLISH) {

    override fun getAllGenres(): Array<BilibiliTag> = arrayOf(
        BilibiliTag("All", -1),
        BilibiliTag("Action", 19),
        BilibiliTag("Adventure", 22),
        BilibiliTag("BL", 3),
        BilibiliTag("Comedy", 14),
        BilibiliTag("Eastern", 30),
        BilibiliTag("Fantasy", 11),
        BilibiliTag("GL", 16),
        BilibiliTag("Harem", 15),
        BilibiliTag("Historical", 12),
        BilibiliTag("Horror", 23),
        BilibiliTag("Mistery", 17),
        BilibiliTag("Romance", 13),
        BilibiliTag("Slice of Life", 21),
        BilibiliTag("Suspense", 41),
        BilibiliTag("Teen", 20)
    )
}

class BilibiliComicsCn : BilibiliComics(BilibiliIntl.SIMPLIFIED_CHINESE) {

    override fun getAllGenres(): Array<BilibiliTag> = arrayOf(
        BilibiliTag("全部", -1),
        BilibiliTag("校园", 18),
        BilibiliTag("都市", 9),
        BilibiliTag("耽美", 3),
        BilibiliTag("少女", 20),
        BilibiliTag("恋爱", 13),
        BilibiliTag("奇幻", 11),
        BilibiliTag("热血", 19),
        BilibiliTag("冒险", 22),
        BilibiliTag("古风", 12),
        BilibiliTag("百合", 16),
        BilibiliTag("玄幻", 30),
        BilibiliTag("悬疑", 41),
        BilibiliTag("科幻", 8)
    )
}

class BilibiliComicsId : BilibiliComics(BilibiliIntl.INDONESIAN) {

    override fun getAllGenres(): Array<BilibiliTag> = arrayOf(
        BilibiliTag("Semua", -1),
        BilibiliTag("Aksi", 19),
        BilibiliTag("Fantasi Timur", 30),
        BilibiliTag("Fantasi", 11),
        BilibiliTag("Historis", 12),
        BilibiliTag("Horror", 23),
        BilibiliTag("Kampus", 18),
        BilibiliTag("Komedi", 14),
        BilibiliTag("Menegangkan", 41),
        BilibiliTag("Remaja", 20),
        BilibiliTag("Romantis", 13)
    )
}

class BilibiliComicsEs : BilibiliComics(BilibiliIntl.SPANISH) {

    override fun getAllGenres(): Array<BilibiliTag> = arrayOf(
        BilibiliTag("Todos", -1),
        BilibiliTag("Adolescencia", 105),
        BilibiliTag("BL", 3),
        BilibiliTag("Ciberdeportes", 104),
        BilibiliTag("Ciencia ficción", 8),
        BilibiliTag("Comedia", 14),
        BilibiliTag("Fantasía occidental", 106),
        BilibiliTag("Fantasía", 11),
        BilibiliTag("Ficción Realista", 116),
        BilibiliTag("GL", 16),
        BilibiliTag("Histórico", 12),
        BilibiliTag("Horror", 23),
        BilibiliTag("Juvenil", 20),
        BilibiliTag("Moderno", 111),
        BilibiliTag("Oriental", 30),
        BilibiliTag("Romance", 13),
        BilibiliTag("Suspenso", 41),
        BilibiliTag("Urbano", 9),
        BilibiliTag("Wuxia", 103)
    )
}
