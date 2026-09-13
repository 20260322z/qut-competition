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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

const val DEFAULT_SERVER = "http://38.207.179.218:18086"
const val DEFAULT_ZHCP = "http://38.207.179.218:18088"
const val DEFAULT_ZHCP_ADMIN = "http://38.207.179.218:18089"
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
    val sentForDeadline: Long? = null,
    val changeSummary: String = ""
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

@Database(entities = [Notice::class, UserState::class], version = 2, exportSchema = false)
abstract class QutDatabase : RoomDatabase() { abstract fun dao(): NoticeDao }

data class Settings(
    val server: String = DEFAULT_SERVER,
    val newsEnabled: Boolean = false,
    val lastSync: String = "",
    val syncCursor: String = "",
    val sourceSuccess: String = "",
    val sourceError: String = "",
    val qqSuccess: String = "",
    val qqError: String = "",
    val zhcpToken: String = "",
    val zhcpRole: String = "",
    val zhcpName: String = "",
    val zhcpCollege: String = "",
    val zhcpClass: String = "",
    val zhcpAdminUrl: String = DEFAULT_ZHCP_ADMIN
)

data class ZhcpRow(
    val rosterId: Long,
    val name: String,
    val studentNo: String,
    val registered: Boolean,
    val total: Double,
    val rank: Int?
)

data class ZhcpMine(
    val total: Double,
    val rank: Int?,
    val items: List<Pair<String, String>>
)

data class SyncResult(val added: List<Notice>, val initial: Boolean, val total: Int)

class Repository(private val context: Context) {
    private val db = Room.databaseBuilder(context, QutDatabase::class.java, "qut.db")
        .addMigrations(object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_states ADD COLUMN changeSummary TEXT NOT NULL DEFAULT ''")
            }
        }).build()
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
    private val qqSuccessKey = stringPreferencesKey("qq_success")
    private val qqErrorKey = stringPreferencesKey("qq_error")
    private val zhcpTokenKey = stringPreferencesKey("zhcp_token")
    private val zhcpRoleKey = stringPreferencesKey("zhcp_role")
    private val zhcpNameKey = stringPreferencesKey("zhcp_name")
    private val zhcpCollegeKey = stringPreferencesKey("zhcp_college")
    private val zhcpClassKey = stringPreferencesKey("zhcp_class")
    private val zhcpAdminKey = stringPreferencesKey("zhcp_admin")

    val settings: Flow<Settings> = prefs.data.map { p ->
        Settings(p[serverKey] ?: DEFAULT_SERVER, p[newsKey] ?: false,
            p[syncKey] ?: "", p[cursorKey] ?: "", p[sourceSuccessKey] ?: "", p[sourceErrorKey] ?: "",
            p[qqSuccessKey] ?: "", p[qqErrorKey] ?: "",
            p[zhcpTokenKey] ?: "", p[zhcpRoleKey] ?: "", p[zhcpNameKey] ?: "",
            p[zhcpCollegeKey] ?: "", p[zhcpClassKey] ?: "", p[zhcpAdminKey] ?: DEFAULT_ZHCP_ADMIN)
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
            it.remove(qqSuccessKey)
            it.remove(qqErrorKey)
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
        val originals = dao.allNotices().associateBy { it.id }
        val previous = originals.keys
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
        for (fresh in received) {
            val old = originals[fresh.id] ?: continue
            val changes = buildList {
                if (old.title != fresh.title) add("标题更新")
                if (old.body != fresh.body) add("正文更新")
                if (old.deadline != fresh.deadline) add("截止时间：${old.deadline?.take(10) ?: "待确认"} → ${fresh.deadline?.take(10) ?: "待确认"}")
                if (old.attachments != fresh.attachments) add("附件更新")
            }
            if (changes.isNotEmpty()) editState(fresh.id) { it.copy(changeSummary = changes.joinToString("；")) }
        }
        dao.saveNotices(received)
        val sources = runCatching {
            getJson(config.server + "/api/v1/sources").getJSONArray("items")
        }.getOrNull()
        prefs.edit {
            it[syncKey] = Instant.now().toString()
            it[cursorKey] = before
            if (sources != null) {
                for (index in 0 until sources.length()) {
                    val source = sources.getJSONObject(index)
                    when (source.optString("id")) {
                        "qq" -> {
                            it[qqSuccessKey] = source.nullableString("last_success") ?: ""
                            it[qqErrorKey] = source.nullableString("last_error") ?: ""
                        }
                        else -> {
                            it[sourceSuccessKey] = source.nullableString("last_success") ?: ""
                            it[sourceErrorKey] = source.nullableString("last_error") ?: ""
                        }
                    }
                }
            }
        }
        for (state in dao.reminders()) Reminders.schedule(context, state.id)
        SyncResult(received.filter { it.id !in previous }, config.syncCursor.isEmpty(), dao.allNotices().size)
    } }

    private val zhcpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS).callTimeout(330, TimeUnit.SECONDS).build()

    suspend fun transcriptPreview(account: String, password: String): String = withContext(Dispatchers.IO) {
        val config = settings.first()
        require(config.zhcpToken.isNotBlank() && config.zhcpRole == "student") { "请先在学业 → 综测中登录本人学生账号" }
        val body = JSONObject().put("account", account).put("password", password).put("consent", "true")
        val request = Request.Builder().url("$DEFAULT_ZHCP/app/academic/transcript")
            .header("Authorization", "Bearer ${config.zhcpToken}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        zhcpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "教务成绩接口暂不可用，可改用 CSV 导入" }
            val json = JSONObject(response.body?.string() ?: error("空响应"))
            require(json.optInt("code") == 200) { json.optString("msg", "读取失败") }
            val rows = json.getJSONObject("data").getJSONArray("items").objects()
            require(rows.isNotEmpty()) { "教务未返回成绩，原数据保持不变" }
            fun csv(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
            "学期,课程,学分,成绩,绩点,状态\n" + rows.joinToString("\n") { row ->
                listOf("semester", "course", "credits", "score", "gpa", "status").joinToString(",") { csv(row.optString(it)) }
            }
        }
    }

    suspend fun zhcpLogin(role: String, account: String, password: String): Settings = withContext(Dispatchers.IO) {
        val body = JSONObject().put("role", role).put("username", account).put("password", password)
        val req = Request.Builder().url("$DEFAULT_ZHCP/app/login")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        zhcpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("登录失败 ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: error("空响应"))
            require(json.optInt("code") == 200) { json.optString("msg", "登录失败") }
            val data = json.optJSONObject("data") ?: json
            val token = data.optString("token").ifBlank { json.optString("token") }
            val user = data.optJSONObject("user") ?: JSONObject()
            prefs.edit {
                it[zhcpTokenKey] = token
                it[zhcpRoleKey] = user.optString("role", role)
                it[zhcpNameKey] = user.optString("nickName")
                it[zhcpCollegeKey] = user.optString("college")
                it[zhcpClassKey] = user.optString("className")
                it[zhcpAdminKey] = data.optString("adminUrl", DEFAULT_ZHCP_ADMIN)
            }
        }
        settings.first()
    }

    suspend fun zhcpLogout() {
        prefs.edit {
            it.remove(zhcpTokenKey); it.remove(zhcpRoleKey); it.remove(zhcpNameKey)
            it.remove(zhcpCollegeKey); it.remove(zhcpClassKey)
        }
    }

    private fun zhcpGet(token: String, path: String): JSONObject {
        val req = Request.Builder().url("$DEFAULT_ZHCP$path")
            .header("Authorization", "Bearer $token").get().build()
        zhcpClient.newCall(req).execute().use {
            if (!it.isSuccessful) error("服务返回 ${it.code}")
            val json = JSONObject(it.body?.string() ?: error("空响应"))
            require(json.optInt("code") == 200) { json.optString("msg", "请求失败") }
            return json
        }
    }

    suspend fun zhcpClass(token: String): List<ZhcpRow> = withContext(Dispatchers.IO) {
        val data = zhcpGet(token, "/app/zhcp/class").optJSONObject("data") ?: JSONObject()
        val rows = data.optJSONArray("rows") ?: JSONArray()
        (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            ZhcpRow(r.optLong("roster_id"), r.optString("student_name"), r.optString("student_no"),
                r.optInt("registered") == 1, r.optDouble("total_score", 0.0),
                if (r.isNull("zhcp_rank")) null else r.optInt("zhcp_rank"))
        }
    }

    suspend fun zhcpMine(token: String): ZhcpMine = withContext(Dispatchers.IO) {
        val data = zhcpGet(token, "/app/zhcp/mine").optJSONObject("data") ?: JSONObject()
        val score = data.optJSONObject("score") ?: JSONObject()
        val items = data.optJSONArray("items") ?: JSONArray()
        ZhcpMine(score.optDouble("total_score", 0.0),
            if (score.isNull("zhcp_rank")) null else score.optInt("zhcp_rank"),
            (0 until items.length()).map {
                val it = items.getJSONObject(it)
                it.optString("title") to "${it.optString("applied_points")} · ${it.optString("authenticity")}"
            })
    }

    suspend fun zhcpUpload(token: String, uri: android.net.Uri): ZhcpMine = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取文件")
        val name = uri.lastPathSegment ?: "materials.zip"
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val req = Request.Builder().url("$DEFAULT_ZHCP/app/zhcp/upload")
            .header("Authorization", "Bearer $token").post(body).build()
        zhcpClient.newCall(req).execute().use {
            if (!it.isSuccessful) error("上传失败 ${it.code}")
            val json = JSONObject(it.body?.string() ?: error("空响应"))
            require(json.optInt("code") == 200) { json.optString("msg", "上传失败") }
        }
        zhcpMine(token)
    }

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

fun Notice.isOfficial(): Boolean = !source.startsWith("QQ群")

fun Notice.hasWebUrl(): Boolean = url.startsWith("http://") || url.startsWith("https://")
