package com.example.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

@JsonClass(generateAdapter = true)
data class StartJobResponse(
    @param:Json(name = "job_id") val jobId: String,
    @param:Json(name = "status") val status: String
)

@JsonClass(generateAdapter = true)
data class JobStatusResponse(
    @param:Json(name = "job_id") val jobId: String,
    @param:Json(name = "status") val status: String,
    @param:Json(name = "message") val message: String? = null
)

interface TitanAnimeApi {

    @GET("/")
    suspend fun checkHealth(): Response<ResponseBody>

    @Multipart
    @POST("stylize/start")
    suspend fun startJob(
        @Part video: MultipartBody.Part
    ): Response<StartJobResponse>

    @GET("stylize/status/{job_id}")
    suspend fun getJobStatus(
        @Path("job_id") jobId: String
    ): Response<JobStatusResponse>

    @Streaming
    @GET("stylize/download/{job_id}")
    suspend fun downloadStylizedVideo(
        @Path("job_id") jobId: String
    ): Response<ResponseBody>
}
