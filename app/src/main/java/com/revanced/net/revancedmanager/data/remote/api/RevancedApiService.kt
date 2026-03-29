package com.revanced.net.revancedmanager.data.remote.api

import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface RevancedApiService {
    @GET
    suspend fun getApps(@Url endpoint: String): Response<JsonElement>
}
