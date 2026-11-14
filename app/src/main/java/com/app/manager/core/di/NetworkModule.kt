package com.app.manager.core.di

import android.util.Log
import com.app.manager.data.local.preferences.PreferencesManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Hilt module for providing network-related dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val HTTP_LOG_TAG = "HttpLog"

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class ApiClient

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class DownloadClient

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * One line per request: method, host+path, status, milliseconds, cached. Runs in release
     * builds too (gated on the debug-logging preference) — headers and bodies are never logged,
     * so no cookies or tokens can leak into the log (upstream 6998db0).
     */
    @Provides
    @Singleton
    fun provideHttpLoggingInterceptor(
        preferencesManager: PreferencesManager
    ): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val start = System.currentTimeMillis()
        val response = chain.proceed(request)
        val enabled = runCatching { preferencesManager.getAppConfig().debugLogging }
            .getOrDefault(false)
        if (enabled) {
            val cached = response.cacheResponse != null && response.networkResponse == null
            Log.i(
                HTTP_LOG_TAG,
                "${request.method} ${request.url.host}${request.url.encodedPath}" +
                    " → ${response.code} ${System.currentTimeMillis() - start}ms" +
                    if (cached) " (cached)" else ""
            )
        }
        response
    }

    @Provides
    @Singleton
    @ApiClient
    fun provideApiOkHttpClient(
        loggingInterceptor: Interceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @DownloadClient
    fun provideDownloadOkHttpClient(
        loggingInterceptor: Interceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@DownloadClient okHttpClient: OkHttpClient): OkHttpClient {
        return okHttpClient
    }

}
