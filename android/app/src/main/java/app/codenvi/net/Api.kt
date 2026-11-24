package app.codenvi.net

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** /api/v1 (docs/design.md §7.3). */
interface CodenviApi {
    @POST("api/v1/pair") suspend fun pair(@Body req: PairRequest): TokenResponse

    @GET("api/v1/installs") suspend fun installs(): List<InstallDto>
    @PATCH("api/v1/installs/{id}") suspend fun renameInstall(@Path("id") id: String, @Body req: RenameRequest): Response<Unit>
    @DELETE("api/v1/installs/{id}") suspend fun revokeInstall(@Path("id") id: String, @Query("confirmSelf") confirmSelf: Boolean? = null): Response<Unit>

    @GET("api/v1/devices") suspend fun devices(): List<DeviceDto>
    @PATCH("api/v1/devices/{id}") suspend fun renameDevice(@Path("id") id: String, @Body req: RenameRequest): Response<Unit>
    @DELETE("api/v1/devices/{id}") suspend fun deleteDevice(@Path("id") id: String): Response<Unit>

    @GET("api/v1/devices/{id}/projects") suspend fun projects(@Path("id") deviceId: String): List<ProjectDto>
    @PATCH("api/v1/projects/{id}") suspend fun patchProject(@Path("id") id: String, @Body req: PatchProjectRequest): Response<Unit>

    @GET("api/v1/projects/{id}/sessions") suspend fun sessions(@Path("id") projectId: String): List<SessionDto>
    @PUT("api/v1/sessions/{id}") suspend fun putSession(@Path("id") id: String, @Body req: PutSessionRequest): Response<Unit>
    @PATCH("api/v1/sessions/{id}") suspend fun patchSession(@Path("id") id: String, @Body req: PatchSessionRequest): Response<Unit>
    @DELETE("api/v1/sessions/{id}") suspend fun deleteSession(@Path("id") id: String): Response<Unit>

    @GET("api/v1/sessions/{id}/messages") suspend fun messages(@Path("id") sessionId: String, @Query("afterSeq") afterSeq: Long,
                                                              @Query("limit") limit: Int = 200): List<MessageDto>
    @POST("api/v1/sessions/{id}/messages") suspend fun sendPrompt(@Path("id") sessionId: String, @Body req: SendPromptRequest): SendPromptResponse
    @DELETE("api/v1/sessions/{id}/messages") suspend fun clearMessages(@Path("id") sessionId: String): Response<Unit>
    @GET("api/v1/sessions/{id}/jobs") suspend fun jobs(@Path("id") sessionId: String): List<JobDto>
    @GET("api/v1/devices/{id}/jobs/active") suspend fun activeJobs(@Path("id") deviceId: String): List<JobDto>

    @POST("api/v1/jobs/{id}/cancel") suspend fun cancelJob(@Path("id") jobId: String): Response<Unit>

    @GET("api/v1/projects/{id}/commits") suspend fun commits(@Path("id") projectId: String): List<CommitDto>
    @POST("api/v1/projects/{id}/control") suspend fun control(@Path("id") projectId: String, @Body req: ControlRequest): Response<Unit>

    @GET("api/v1/catalog/models") suspend fun models(): List<ModelCatalogEntry>
}
