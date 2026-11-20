package com.openegg.app.map

import android.content.Context
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit

/** One-time MapLibre setup. Safe to call repeatedly from any composable/screen before creating a MapView. */
object MapInit {
    /** Generic client identification for tile servers — deliberately no device, account or personal data. */
    const val USER_AGENT = "OpenEgg"

    @Volatile private var done = false

    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            MapLibre.getInstance(context.applicationContext)
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
                }
                .build()
            HttpRequestUtil.setOkHttpClient(client)
            done = true
        }
    }
}
