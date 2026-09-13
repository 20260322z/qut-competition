package cn.qingli.competition

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

@Entity(tableName = "records", primaryKeys = ["kind", "id"])
data class StudentRecord(val kind: String, val id: String, val json: String, val updated: Long = System.currentTimeMillis()) {
    fun data() = JSONObject(json)
}
@Dao
interface StudentDao {
    @Query("SELECT * FROM records ORDER BY updated DESC") fun observe(): Flow<List<StudentRecord>>
    @Query("SELECT * FROM records WHERE kind=:kind AND id=:id") suspend fun get(kind: String, id: String): StudentRecord?
    @Upsert suspend fun put(record: StudentRecord)
    @Query("DELETE FROM records WHERE kind=:kind AND id=:id") suspend fun delete(kind: String, id: String)
    @Transaction suspend fun mergeGrades(incoming:List<StudentRecord>){
        val snapshot=JSONArray()
        incoming.forEach{r->
            require(r.kind=="grade")
            snapshot.put(JSONObject().put("id",r.id).put("before",get("grade",r.id)?.json?:JSONObject.NULL).put("after",r.json))
            put(r)
        }
        put(StudentRecord("draft","grade-import-backup",JSONObject().put("rows",snapshot).toString()))
    }
    @Transaction suspend fun undoGradeImport(){
        val snapshot=get("draft","grade-import-backup")?.data()?.getJSONArray("rows")?.objects()?:error("没有可撤销的导入")
        snapshot.forEach{r->require(get("grade",r.getString("id"))?.json==r.getString("after")){"导入后有课程被修改，为保护新记录请先手动核对"}}
        snapshot.forEach{r->if(r.isNull("before"))delete("grade",r.getString("id")) else put(StudentRecord("grade",r.getString("id"),r.getString("before")))}
        delete("draft","grade-import-backup")
    }
}
@Database(entities = [StudentRecord::class], version = 1, exportSchema = false)
abstract class StudentDatabase: RoomDatabase() { abstract fun dao(): StudentDao }

/** Account/server scoped app-private workspaces. Existing records retain their first owner. */
class StudentRepository(val context: Context) {
    private val auth = context.getSharedPreferences("student_auth", Context.MODE_PRIVATE)
    private val partitions = context.getSharedPreferences("student_partitions", Context.MODE_PRIVATE)
    private fun identity() = if(auth.getString("id", "").isNullOrBlank()) "guest" else auth.getString("server", "")+"|"+auth.getString("id", "")
    private val workspace = MutableStateFlow(identity())
    private val legacyOwner = partitions.getString("legacy_owner",null) ?: identity().also{partitions.edit().putString("legacy_owner",it).commit()}
    private val databases = mutableMapOf<String,StudentDatabase>()
    private fun daoFor(owner:String):StudentDao = synchronized(databases){
        databases.getOrPut(owner){
            val suffix=java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString(""){"%02x".format(it)}
            Room.databaseBuilder(context.applicationContext,StudentDatabase::class.java,if(owner==legacyOwner)"student-workspace.db" else "student-$suffix.db").build()
        }.dao()
    }
    val dao get()=daoFor(workspace.value)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val records = workspace.flatMapLatest{daoFor(it).observe()}
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val account = MutableStateFlow(auth.getString("email", "") ?: "")
    private val observer=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.IO)
    init {
        observer.launchWorkspaceObserver()
    }
    private fun kotlinx.coroutines.CoroutineScope.launchWorkspaceObserver() = launch {
        context.repository().settings.collect{settings->
            val valid=auth.getString("server", "")==settings.server
            workspace.value=if(valid)identity() else "guest"
            account.value=if(valid)auth.getString("email", "")?:"" else ""
        }
    }
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("student_session", null) as? javax.crypto.SecretKey)?.let { return it }
        return javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder("student_session", android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    private fun token(): String = runCatching {
        val stored = auth.getString("token", null) ?: return ""
        val bytes = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
        javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }.getOrDefault("")
    suspend fun login(result: JSONObject) = withContext(Dispatchers.IO) {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(result.getString("token").toByteArray())
        auth.edit().putString("token", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
            .putString("email", result.getString("email")).putString("id", result.getString("id"))
            .putString("server", context.repository().settings.first().server).commit()
        account.value = result.getString("email")
        workspace.value = identity()
    }
    suspend fun logout() {
        runCatching { api("auth/logout", "POST", JSONObject()) }
        auth.edit().clear().apply(); account.value = ""; workspace.value = "guest"
    }
    fun owner() = if(account.value.isBlank()) "" else auth.getString("id", "") ?: ""
    suspend fun save(kind: String, data: JSONObject, id: String = UUID.randomUUID().toString()): String {
        dao.put(StudentRecord(kind, id, data.toString())); return id
    }
    suspend fun api(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val server = context.repository().settings.first().server
        val request = Request.Builder().url("$server/api/v1/student/$path")
        val storedServer = auth.getString("server", "")
        // Never forward a user's bearer token when the server address changes.
        if (storedServer == server && token().isNotEmpty()) request.header("Authorization", "Bearer ${token()}")
        if (method != "GET") request.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(request.build()).execute().use { response ->
            val raw = response.body?.string() ?: "{}"
            val result = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) {
                val detail = result.opt("detail")
                if (response.code == 404 && detail == "Not Found") error("当前服务器尚未部署学生工作台接口，请先更新后端；本机工具仍可使用")
                error(if (detail is String) detail else "请求未完成（${response.code}），请检查填写内容或稍后重试")
            }
            result
        }
    }
    suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        val server = context.repository().settings.first().server
        val builder = Request.Builder().url("$server/api/v1/student/$path")
        if (auth.getString("server", "")==server) builder.header("Authorization", "Bearer ${token()}")
        client.newCall(builder.build()).execute().use {
            require(it.isSuccessful) { "文件无法下载，可能已经取消分享" }
            it.body?.bytes() ?: error("文件为空")
        }
    }
}

private var studentInstance: StudentRepository? = null
fun Context.students(): StudentRepository = synchronized(StudentRepository::class.java) {
    studentInstance ?: StudentRepository(applicationContext).also { studentInstance = it }
}
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

data class Grade(val semester: String, val course: String, val credits: Double, val score: Double?, val gpa: Double?, val status: String, val note: String = "") {
    val included get() = status == "正常" && score != null && credits > 0
    fun json() = JSONObject().put("semester", semester).put("course", course).put("credits", credits)
        .put("score", score ?: JSONObject.NULL).put("gpa", gpa ?: JSONObject.NULL).put("status", status).put("note", note)
    companion object {
        fun from(j: JSONObject) = Grade(j.optString("semester"), j.optString("course"), j.optDouble("credits", 0.0),
            if (j.isNull("score")) null else j.optDouble("score"), if (j.isNull("gpa")) null else j.optDouble("gpa"), j.optString("status", "正常"), j.optString("note"))
    }
}
data class GradeSummary(val credits: Double, val average: Double?, val gpa: Double?, val gpaCredits: Double, val excluded: Int)
fun summarizeGrades(grades: List<Grade>): GradeSummary {
    val valid = grades.filter { it.included }
    val credits = valid.sumOf { it.credits }
    val gp = valid.filter { it.gpa != null }
    val gpCredits = gp.sumOf { it.credits }
    return GradeSummary(credits, if (credits > 0) valid.sumOf { it.score!! * it.credits } / credits else null,
        if (gpCredits > 0) gp.sumOf { it.gpa!! * it.credits } / gpCredits else null, gpCredits, grades.size - valid.size)
}
fun targetAverage(current: Double, currentCredits: Double, futureCredits: Double, target: Double): Double {
    require(currentCredits >= 0 && futureCredits > 0 && target in 0.0..100.0)
    return (target * (currentCredits + futureCredits) - current * currentCredits) / futureCredits
}
/** CSV with quoted commas/newlines; exact header prevents silently shifted transcripts. */
fun parseGradeCsv(text: String): List<Grade> {
    require(text.length <= 1_000_000) { "成绩文件过大" }
    val rows = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val field = StringBuilder()
    var quoted = false; var i = 0
    val input = text.removePrefix("\uFEFF").replace("\r\n", "\n")
    while (i < input.length) {
        val c = input[i]
        when {
            c == '"' && quoted && i + 1 < input.length && input[i+1] == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { row += field.toString().trim(); field.clear() }
            c == '\n' && !quoted -> { row += field.toString().trim(); field.clear(); if (row.any { it.isNotBlank() }) rows += row.toList(); row.clear() }
            else -> field.append(c)
        }; i++
    }
    require(!quoted) { "CSV 引号没有闭合" }
    row += field.toString().trim(); if (row.any { it.isNotBlank() }) rows += row.toList()
    require(rows.size > 1 && rows.size <= 501) { "需要表头和 1—500 门课程" }
    val head = rows.first()
    require(head.take(6) == listOf("学期", "课程", "学分", "成绩", "绩点", "状态")) { "表头应为：学期,课程,学分,成绩,绩点,状态" }
    val result = rows.drop(1).mapIndexed { index, r ->
        require(r.size >= 6) { "第 ${index+2} 行缺少字段" }
        val credits = r[2].toDoubleOrNull(); val score = r[3].toDoubleOrNull(); val gpa = r[4].toDoubleOrNull()
        require(r[0].isNotBlank() && r[1].isNotBlank() && credits != null && credits.isFinite() && credits in 0.0..50.0) { "第 ${index+2} 行学期、课程或学分不正确" }
        require(r[3].isBlank() || (score != null && score.isFinite() && score in 0.0..100.0)) { "第 ${index+2} 行成绩应为 0—100 或留空，等级成绩请先按学校规则核对" }
        require(r[4].isBlank() || (gpa != null && gpa.isFinite() && gpa in 0.0..5.0)) { "第 ${index+2} 行绩点无效，缺失可留空" }
        require(r[5] in listOf("正常", "未发布", "补考", "重修", "免修", "不计入")) { "第 ${index+2} 行状态不支持" }
        Grade(r[0],r[1],credits,score,gpa,r[5],"用户导入，需核对教务原件")
    }
    require(result.map { it.semester to it.course }.distinct().size == result.size) { "同一学期出现同名课程，请先核对重修或重复行" }
    return result
}
