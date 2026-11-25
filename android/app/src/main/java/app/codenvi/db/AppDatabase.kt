package app.codenvi.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Offline cache (docs/design.md §10.2). Content stays encrypted at rest; it is decrypted on demand for display.

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val keyVersion: Int,
    val keyCheck: ByteArray,
    val online: Boolean,
    val lastOnlineAtMs: Long?,
    val createdAtMs: Long,
)

@Entity(tableName = "projects", indices = [Index("deviceId")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val encMeta: ByteArray,
    val defaultSessionId: String?,
    val sortOrder: Int,
    val updatedAtMs: Long,
)

@Entity(tableName = "sessions", indices = [Index("projectId")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val encMeta: ByteArray,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(tableName = "messages", indices = [Index(value = ["sessionId", "seq"])])
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val seq: Long,
    val role: String,
    val jobId: String?,
    val ciphertext: ByteArray,
    val clientCreatedAtMs: Long,
    val createdAtMs: Long,
)

/** One assistant reply with where it belongs (result row of [CodenviDao.replyRows]). */
data class ReplyRow(val sessionId: String, val projectId: String, val deviceId: String, val seq: Long, val createdAtMs: Long)

@Entity(tableName = "jobs", indices = [Index("sessionId"), Index("projectId")])
data class JobEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val projectId: String,
    val sessionId: String,
    val promptMessageId: String,
    val status: String,
    val errorCode: String?,
    val encError: ByteArray?,
    val model: String?,
    val effort: String?,
    val queuedAtMs: Long,
    val finishedAtMs: Long?,
)

@Entity(tableName = "commits", indices = [Index("projectId")])
data class CommitEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val jobId: String?,
    val gitHash: String,
    val kind: String,
    val encMeta: ByteArray,
    val revertedBy: String?,
    val createdAtMs: Long,
)

@Dao
interface CodenviDao {
    // Devices
    @Query("SELECT * FROM devices ORDER BY createdAtMs") fun devices(): Flow<List<DeviceEntity>>
    @Query("SELECT * FROM devices WHERE id = :id") suspend fun device(id: String): DeviceEntity?
    @Upsert suspend fun upsertDevices(items: List<DeviceEntity>)
    @Query("DELETE FROM devices WHERE id NOT IN (:keep)") suspend fun deleteDevicesExcept(keep: List<String>)
    @Query("UPDATE devices SET online = :online WHERE id = :id") suspend fun setOnline(id: String, online: Boolean)

    // Projects
    @Query("SELECT * FROM projects ORDER BY sortOrder, id") fun allProjects(): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects WHERE deviceId = :deviceId ORDER BY sortOrder") fun projects(deviceId: String): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun project(id: String): ProjectEntity?
    @Query("SELECT * FROM projects WHERE id = :id") fun projectFlow(id: String): Flow<ProjectEntity?>
    @Upsert suspend fun upsertProjects(items: List<ProjectEntity>)
    @Query("DELETE FROM projects WHERE deviceId = :deviceId AND id NOT IN (:keep)") suspend fun deleteProjectsExcept(deviceId: String, keep: List<String>)
    @Query("DELETE FROM projects WHERE deviceId NOT IN (:keepDevices)") suspend fun deleteProjectsOfMissingDevices(keepDevices: List<String>)

    // Sessions
    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY createdAtMs") fun sessions(projectId: String): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAtMs DESC") suspend fun sessionsByRecent(projectId: String): List<SessionEntity>
    @Query("SELECT * FROM sessions WHERE id = :id") fun sessionFlow(id: String): Flow<SessionEntity?>
    @Upsert suspend fun upsertSessions(items: List<SessionEntity>)
    @Query("DELETE FROM sessions WHERE projectId = :projectId AND id NOT IN (:keep)") suspend fun deleteSessionsExcept(projectId: String, keep: List<String>)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: String)

    // Messages
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND role != 'progress' ORDER BY seq") fun messages(sessionId: String): Flow<List<MessageEntity>>
    @Query("SELECT coalesce(max(seq), 0) FROM messages WHERE sessionId = :sessionId") suspend fun maxSeq(sessionId: String): Long
    @Upsert suspend fun upsertMessages(items: List<MessageEntity>)
    @Query("DELETE FROM messages WHERE sessionId = :sessionId") suspend fun clearMessages(sessionId: String)
    /** Every assistant reply with its session, project and device (for the unseen-reply dots and counts). */
    @Query("SELECT m.sessionId AS sessionId, s.projectId AS projectId, p.deviceId AS deviceId, m.seq AS seq, m.createdAtMs AS createdAtMs " +
        "FROM messages m JOIN sessions s ON s.id = m.sessionId JOIN projects p ON p.id = s.projectId WHERE m.role = 'assistant'")
    fun replyRows(): Flow<List<ReplyRow>>
    @Query("SELECT * FROM messages WHERE role = 'assistant' AND sessionId IN (SELECT id FROM sessions WHERE projectId = :projectId) ORDER BY createdAtMs DESC LIMIT :limit")
    fun recentReplies(projectId: String, limit: Int): Flow<List<MessageEntity>>

    // Jobs
    @Query("SELECT * FROM jobs WHERE sessionId = :sessionId") fun jobs(sessionId: String): Flow<List<JobEntity>>
    @Query("SELECT * FROM jobs WHERE status IN ('queued','delivered','running','waiting_login')") fun activeJobs(): Flow<List<JobEntity>>
    @Query("SELECT * FROM jobs WHERE id = :id") suspend fun job(id: String): JobEntity?
    @Upsert suspend fun upsertJobs(items: List<JobEntity>)
    @Query("UPDATE jobs SET status = :status, errorCode = :errorCode, encError = :encError, finishedAtMs = :finishedAtMs WHERE id = :id")
    suspend fun updateJob(id: String, status: String, errorCode: String?, encError: ByteArray?, finishedAtMs: Long?)
    @Query("DELETE FROM jobs WHERE sessionId = :sessionId") suspend fun clearJobs(sessionId: String)

    // Commits
    @Query("SELECT * FROM commits WHERE projectId = :projectId ORDER BY createdAtMs DESC") fun commits(projectId: String): Flow<List<CommitEntity>>
    @Upsert suspend fun upsertCommits(items: List<CommitEntity>)

    @Query("DELETE FROM devices") suspend fun wipeDevices()
    @Query("DELETE FROM projects") suspend fun wipeProjects()
    @Query("DELETE FROM sessions") suspend fun wipeSessions()
    @Query("DELETE FROM messages") suspend fun wipeMessages()
    @Query("DELETE FROM jobs") suspend fun wipeJobs()
    @Query("DELETE FROM commits") suspend fun wipeCommits()
}

@Database(
    entities = [DeviceEntity::class, ProjectEntity::class, SessionEntity::class, MessageEntity::class, JobEntity::class, CommitEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): CodenviDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "codenvi.db")
                .fallbackToDestructiveMigration() // it is only a cache; the server holds the history
                .build()
    }
}
