package com.secureguard.mdm.utils

import android.net.Network
import com.secureguard.mdm.data.model.NetfreeUser
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object NetfreeChecker {
    private const val TAG = "NetfreeChecker"

    suspend fun isNetfreeFiltered(network: Network? = null): Boolean {
        val client = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }

        return try {
            FileLogger.log(TAG, "Attempting to fetch Netfree status from API...")
            val user: NetfreeUser = client.get("https://api.internal.netfree.link/user/0").body()
            FileLogger.log(TAG, "API call successful. isNetFree=${user.isNetFree}")
            user.isNetFree
        } catch (e: Exception) {
            FileLogger.log(TAG, "ERROR fetching Netfree status: ${e.message}")
            false
        } finally {
            client.close()
        }
    }
}