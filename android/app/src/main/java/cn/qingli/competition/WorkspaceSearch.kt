package cn.qingli.competition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun WorkspaceSearch(repo:StudentRepository,records:List<StudentRecord>,notices:List<NoticeItem>,openNotice:(NoticeItem)->Unit,navigate:(String)->Unit,back:()->Unit){
    var query by rememberSaveable{mutableStateOf("")}
    val catalog=remember{JSONArray(repo.context.assets.open("contest_sources.json").bufferedReader().use{it.readText()}).objects()}
    var contest by rememberSaveable{mutableStateOf("")}
    var reading by remember{mutableStateOf<JSONObject?>(null)}
    var files by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var posts by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var post by remember{mutableStateOf<JSONObject?>(null)}
    var cloudQuery by remember{mutableStateOf("")}
    if(contest.isNotBlank()){
        BackHandler{contest=""};ContestDetail(repo,contest,catalog.first{it.optString("id")==contest}){contest=""};return
    }
    reading?.let{f->BackHandler{reading=null};LibraryReader(repo,f,f.optInt("public")==1){reading=null};return}
    post?.let{p->BackHandler{post=null};PostDetailScreen(repo,p){post=null};return}
    StudentPage("搜索工作台","本机内容即时搜索，云端搜索仅返回你有权访问的内容",back){
        OutlinedTextField(query,{query=it.take(100)},label={Text("赛事、通知、课程、资料或经历关键词")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        if(query.isNotBlank()){
            val matched=catalog.filter{it.optString("name").contains(query,true)||it.optString("current_name").contains(query,true)||it.optJSONArray("aliases").toString().contains(query,true)}
            if(matched.isNotEmpty()){
                IosSection("赛事 · ${matched.size}")
                matched.take(12).forEach{c->StudentCard(c.optString("current_name").ifBlank{c.optString("name")},sourceStatus(c.optString("verification")),onClick={contest=c.optString("id")})}
            }
            val localNotices=notices.filter{it.notice.title.contains(query,true)||it.notice.body.contains(query,true)}
            if(localNotices.isNotEmpty()){
                IosSection("已缓存通知 · ${localNotices.size}")
                localNotices.take(15).forEach{n->StudentCard(n.notice.title,n.notice.publishedAt.take(10),onClick={openNotice(n)})}
            }
            val personal=records.filter{it.kind!="draft"&&it.json.contains(query,true)}
            if(personal.isNotEmpty()){
                IosSection("个人记录 · ${personal.size}")
                personal.take(15).forEach{r->
                    val d=r.data();StudentCard(d.optString("title").ifBlank{d.optString("course").ifBlank{"个人记录"}},listOf(d.optString("semester"),d.optString("text"),d.optString("note"),d.optString("due"),d.optString("result")).filter{it.isNotBlank()}.joinToString("\n").take(300),onClick={navigate(when(r.kind){"resume"->"resume";"target"->"targets";"application"->"applications";"grade"->"agent_study";"portfolio"->"agent_portfolio";else->"tasks"})})
                }
            }
            OnlineAction("继续搜索云端资料与经验"){
                val encoded=java.net.URLEncoder.encode(query,"UTF-8")
                val content=repo.api("library/search?q=$encoded").getJSONArray("items").objects()
                val publicFiles=repo.api("resources?q=$encoded").getJSONArray("items").objects().onEach{it.put("public",1)}
                val privateFiles=if(repo.owner().isNotBlank())repo.api("files?q=$encoded").getJSONArray("items").objects() else emptyList()
                files=(content+publicFiles+privateFiles).distinctBy{it.optString("id")}
                posts=repo.api("posts?q=$encoded").getJSONArray("items").objects();cloudQuery=query
                "找到 ${files.size} 份资料、${posts.size} 篇经验；正文检索只覆盖已经成功解析的文件"
            }
            if(cloudQuery==query){
                files.forEach{f->StudentCard(f.optString("name"),f.optString("excerpt").ifBlank{f.optJSONObject("data")?.optString("context")?:"资料文件"},onClick={reading=f})}
                posts.forEach{p->StudentCard(p.optString("title"),p.optString("body").take(180),onClick={post=p})}
            }
            if(matched.isEmpty()&&localNotices.isEmpty()&&personal.isEmpty())Text("本机没有匹配内容，可继续查云端或换一个关键词。",fontSize=13.sp,color=IosMuted)
        }else Text("例如：数学建模、高等数学、复试、项目经历。",fontSize=13.sp,color=IosMuted)
    }
}
