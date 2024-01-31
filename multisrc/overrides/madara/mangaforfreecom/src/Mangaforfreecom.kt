package eu.kanade.tachiyomi.extension.all.mangaforfreecom

import android.annotation.SuppressLint
import eu.kanade.tachiyomi.multisrc.madara.Madara
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class Mangaforfreecom : Madara("Mangaforfree.com", "https://mangaforfree.com", "en") {
    override val client = getUnsafeOkHttpClient()

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        val trustAllCerts =
            arrayOf<TrustManager>(
                @SuppressLint("CustomX509TrustManager")
                object : X509TrustManager {
                    @SuppressLint("TrustAllX509TrustManager")
                    override fun checkClientTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?,
                    ) {
                    }

                    @SuppressLint("TrustAllX509TrustManager")
                    override fun checkServerTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?,
                    ) {
                    }

                    override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
                },
            )

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        val sslSocketFactory = sslContext.socketFactory
        return super.client.newBuilder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }.build()
    }
}
