package cn.qingli.competition

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

const val DEFAULT_SERVER = "http://38.207.179.218:18086"
const val WECHAT_REFERENCE = "https://mp.weixin.qq.com/s/BvhMwEWH8_bJdys8FNIByw"
private val Context.preferenceStore by preferencesDataStore("qut_settings")

@Entity(tableName = "notices")
data class Notice(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val publishedAt: String,
    val category: String,
    val summary: String,
    val body: String,
    val deadline: String?,
    val deadlineEvidence: String?,
    val attachments: String,
    val images: String,
    val updatedAt: String,
    val source: String
)

@Entity(tableName = "user_states")
data class UserState(
    @PrimaryKey val id: String,
    val favorite: Boolean = false,
    val read: Boolean = false,
    val reminder: Boolean = false,
    val customDeadline: Long? = null,
    val sentForDeadline: Long? = null
)

data class NoticeItem(val notice: Notice, val state: UserState = UserState(notice.id)) {
    val deadlineMillis: Long? get() = state.customDeadline ?: notice.deadline?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    }
}

@Dao
interface NoticeDao {
    @Query("SELECT * FROM notices ORDER BY publishedAt DESC, id DESC")
    fun observeNotices(): Flow<List<Notice>>
    @Query("SELECT * FROM user_states")
    fun observeStates(): Flow<List<UserState>>
    @Query("SELECT * FROM notices")
    suspend fun allNotices(): List<Notice>
    @Query("SELECT * FROM user_states WHERE reminder=1")
    suspend fun reminders(): List<UserState>
    @Query("SELECT * FROM notices WHERE id=:id")
    suspend fun notice(id: String): Notice?
    @Query("SELECT * FROM user_states WHERE id=:id")
    suspend fun state(id: String): UserState?
    @Upsert suspend fun saveNotices(notices: List<Notice>)
    @Upsert suspend fun saveState(state: UserState)
}

@Database(entities = [Notice::class, UserState::class], version = 1, exportSchema = false)
abstract class QutDatabase : RoomDatabase() { abstract fun dao(): NoticeDao }

data class Settings(
    val server: String = DEFAULT_SERVER,
    val newsEnabled: Boolean = false,
    val lastSync: String = "",
    val syncCursor: String = "",
    val sourceSuccess: String = "",
    val sourceError: String = ""
)

data class SyncResult(val added: List<Notice>, val initial: Boolean, val total: Int)

class Repository(private val context: Context) {
    private val db = Room.databaseBuilder(context, QutDatabase::class.java, "qut.db").build()
    val dao = db.dao()
    private val prefs = context.preferenceStore
    private val syncMutex = Mutex()
    private val stateMutex = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    private val serverKey = stringPreferencesKey("server")
    private val newsKey = booleanPreferencesKey("news")
    private val syncKey = stringPreferencesKey("last_sync")
    private val cursorKey = stringPreferencesKey("cursor")
    private val sourceSuccessKey = stringPreferencesKey("source_success")
    private val sourceErrorKey = stringPreferencesKey("source_error")

    val settings: Flow<Settings> = prefs.data.map { p ->
        Settings(p[serverKey] ?: DEFAULT_SERVER, p[newsKey] ?: false,
            p[syncKey] ?: "", p[cursorKey] ?: "", p[sourceSuccessKey] ?: "", p[sourceErrorKey] ?: "")
    }
    val items: Flow<List<NoticeItem>> = combine(dao.observeNotices(), dao.observeStates()) { notices, states ->
        val index = states.associateBy { it.id }
        notices.map { NoticeItem(it, index[it.id] ?: UserState(it.id)) }
    }

    suspend fun editState(id: String, edit: (UserState) -> UserState) = stateMutex.withLock {
        dao.saveState(edit(dao.state(id) ?: UserState(id)))
    }

    suspend fun setNews(enabled: Boolean) { prefs.edit { it[newsKey] = enabled } }

    suspend fun setServer(raw: String): String = syncMutex.withLock {
        val input = raw.trim().trimEnd('/')
        val normalized = (if (input.contains("://")) input else "http://$input").toHttpUrl()
        require(normalized.username.isEmpty() && normalized.password.isEmpty() && normalized.query == null && normalized.fragment == null) {
            "服务地址不能包含账号、密码或查询参数"
        }
        val value = normalized.toString().trimEnd('/')
        testConnection(value)
        prefs.edit {
            it[serverKey] = value
            it.remove(cursorKey)
            it.remove(syncKey)
            it.remove(sourceSuccessKey)
            it.remove(sourceErrorKey)
        }
        value
    }

    suspend fun testConnection(server: String): Int = withContext(Dispatchers.IO) {
        val json = getJson(server.trimEnd('/') + "/health")
        require(json.optString("status") == "ok" && json.has("notice_count")) { "该地址不是竞赛通服务" }
        json.getInt("notice_count")
    }

    private fun getJson(url: String): JSONObject {
        client.newCall(Request.Builder().url(url).get().build()).execute().use {
            if (!it.isSuccessful) error("服务返回 ${it.code}")
            val body = it.body?.string() ?: error("服务响应为空")
            return JSONObject(body)
        }
    }

    suspend fun sync(): SyncResult = syncMutex.withLock { withContext(Dispatchers.IO) {
        val config = settings.first()
        val previous = dao.allNotices().map { it.id }.toSet()
        val received = mutableListOf<Notice>()
        var page = 1
        var before = ""
        var total: Int
        do {
            val url = (config.server + "/api/v1/notices").toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString()).addQueryParameter("page_size", "100")
            if (config.syncCursor.isNotEmpty()) url.addQueryParameter("updated_since", config.syncCursor)
            if (before.isNotEmpty()) url.addQueryParameter("sync_before", before)
            val result = getJson(url.build().toString())
            if (before.isEmpty()) before = result.getString("sync_before")
            total = result.getInt("total")
            val array = result.getJSONArray("items")
            for (i in 0 until array.length()) received += parseNotice(array.getJSONObject(i))
            require(array.length() > 0 || received.size >= total) { "同步数据不完整，请重试" }
            page++
        } while (received.size < total && page <= 100)
        require(received.size >= total) { "同步数据过多，请更新 App" }
        dao.saveNotices(received)
        val source = runCatching {
            getJson(config.server + "/api/v1/sources").getJSONArray("items").getJSONObject(0)
        }.getOrNull()
        prefs.edit {
            it[syncKey] = Instant.now().toString()
            it[cursorKey] = before
            if (source != null) {
                it[sourceSuccessKey] = source.nullableString("last_success") ?: ""
                it[sourceErrorKey] = source.nullableString("last_error") ?: ""
            }
        }
        for (state in dao.reminders()) Reminders.schedule(context, state.id)
        SyncResult(received.filter { it.id !in previous }, config.syncCursor.isEmpty(), dao.allNotices().size)
    } }

    companion object {
        fun parseNotice(j: JSONObject) = Notice(
            j.getString("id"), j.getString("url"), j.getString("title"), j.getString("published_at"),
            j.getString("category"), j.getString("summary"), j.getString("body"), j.nullableString("deadline"),
            j.nullableString("deadline_evidence"), j.optJSONArray("attachments")?.toString() ?: "[]",
            j.optJSONArray("images")?.toString() ?: "[]", j.getString("updated_at"), j.getString("source")
        )
    }
}

fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun Notice.attachmentList(): List<Pair<String, String>> = runCatching {
    val array = JSONArray(attachments)
    (0 until array.length()).map { array.getJSONObject(it).let { a -> a.getString("name") to a.getString("url") } }
}.getOrDefault(emptyList())

fun Notice.imageList(): List<String> = runCatching {
    val array = JSONArray(images)
    (0 until array.length()).map { array.getString(it) }
}.getOrDefault(emptyList())
