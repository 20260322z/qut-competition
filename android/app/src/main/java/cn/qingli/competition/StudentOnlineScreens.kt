package cn.qingli.competition

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Composable
fun OnlineAction(label: String, action: suspend ()->String) {
    val scope=rememberCoroutineScope();var busy by remember{mutableStateOf(false)};var result by remember{mutableStateOf("")}
    Button(enabled=!busy,onClick={scope.launch{busy=true;result=runCatching{action()}.getOrElse{it.message?:"网络操作失败，请重试"};busy=false}},modifier=Modifier.heightIn(min=48.dp)){Text(if(busy)"处理中…" else label)}
    if(result.isNotBlank())Text(result)
}

@Composable
fun AccountScreen(repo: StudentRepository, records: List<StudentRecord>, back:()->Unit) {
    val account by repo.account.collectAsState(); var email by rememberSaveable{mutableStateOf("")};var challenge by rememberSaveable{mutableStateOf("")};var code by rememberSaveable{mutableStateOf("")}
    var pendingConflicts by remember{mutableStateOf<List<Triple<StudentRecord,JSONObject,Int>>>(emptyList())}
    StudentPage("账号与同步","邮箱登录不代表学校身份认证；本机资料不会自动公开",back) {
        if(account.isBlank()) {
            OutlinedTextField(email,{email=it},label={Text("邮箱，例如 123456@qq.com")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OnlineAction("发送登录验证码"){challenge=repo.api("auth/code","POST",JSONObject().put("email",email)).getString("challenge");"验证码已提交发送，10 分钟内有效"}
            if(challenge.isNotBlank()) {
                OutlinedTextField(code,{code=it.take(6)},label={Text("6 位验证码")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OnlineAction("验证并登录"){repo.login(repo.api("auth/verify","POST",JSONObject().put("challenge",challenge).put("code",code)));"登录成功，游客收藏仍在本机"}
            }
        } else {
            StudentCard(account,"本机数据仍然保留。以下操作会把所选的本机记录备份到当前账号，文件需另行上传。")
            var consent by remember{mutableStateOf(false)}
            Row { Checkbox(consent,{consent=it});Text("我确认将本机成绩、档案、目标、任务等记录备份到此账号") }
            if(consent) OnlineAction("合并云端与本机记录"){
                var uploaded=0;var downloaded=0;var conflicts=0
                val conflictRecords=mutableListOf<Triple<StudentRecord,JSONObject,Int>>()
                val favorites=repo.context.repository().dao.allNotices().mapNotNull{n->repo.context.repository().dao.state(n.id)?.takeIf{it.favorite||it.read}?.let{s->StudentRecord("favorite",n.id,JSONObject().put("favorite",s.favorite).put("read",s.read).toString())}}
                val local=records.filter{it.kind in setOf("profile","grade","portfolio","target","application","task","interview","resume","draft","scenario")}+favorites
                for(kind in setOf("profile","grade","portfolio","target","application","task","interview","resume","favorite","draft","scenario")) {
                    val remote=repo.api("records/$kind").getJSONArray("items").objects().associateBy{it.getString("id")}
                    val own=local.filter{it.kind==kind}.associateBy{it.id}
                    for(record in own.values) {
                        val r=remote[record.id]
                        if(r==null) {repo.api("records/$kind/${record.id}","PUT",JSONObject().put("data",record.data()).put("revision",0));uploaded++}
                        else if(kind=="favorite") {
                            val combined=JSONObject().put("favorite",record.data().optBoolean("favorite")||r.getJSONObject("data").optBoolean("favorite")).put("read",record.data().optBoolean("read")||r.getJSONObject("data").optBoolean("read"))
                            repo.api("records/$kind/${record.id}","PUT",JSONObject().put("data",combined).put("revision",r.getInt("revision")))
                            repo.context.repository().editState(record.id){it.copy(favorite=combined.getBoolean("favorite"),read=combined.getBoolean("read"))}
                        } else if(record.data().toString()!=r.getJSONObject("data").toString()) {conflicts++;conflictRecords+=Triple(record,r.getJSONObject("data"),r.getInt("revision"))}
                    }
                    for((id,r) in remote) if(id !in own) {
                        if(kind=="favorite") {val d=r.getJSONObject("data");repo.context.repository().editState(id){it.copy(favorite=it.favorite||d.optBoolean("favorite"),read=it.read||d.optBoolean("read"))}}
                        else repo.save(kind,r.getJSONObject("data"),id)
                        downloaded++
                    }
                }
                pendingConflicts=conflictRecords
                "新增备份 $uploaded 条，恢复 $downloaded 条；$conflicts 条同名冲突保留两端原值，请在下方比较后选择。"
            }
            pendingConflicts.forEach{conflict->val (record,remote,revision)=conflict
                fun readable(j:JSONObject)=j.keys().asSequence().filter{!it.startsWith("_")}.map{key->"$key：${j.opt(key)}"}.joinToString("\n").take(3000)
                StudentCard("请选择保留的内容","本机：\n${readable(record.data())}\n\n云端：\n${readable(remote)}",actions={
                    OnlineAction("用本机内容更新云端"){
                        repo.api("records/${record.kind}/${record.id}","PUT",JSONObject().put("data",record.data()).put("revision",revision));pendingConflicts=pendingConflicts.filter{it!=conflict};"云端已更新"
                    }
                    OnlineAction("用云端内容替换本机"){
                        repo.save("draft",JSONObject().put("title","替换前备份").put("text",record.json))
                        repo.save(record.kind,remote,record.id);pendingConflicts=pendingConflicts.filter{it!=conflict};"本机已更新，替换前内容已保留为本机备份草稿"
                    }
                })
            }
            OnlineAction("退出邮箱账号"){repo.logout();"已退出，本机数据和学校账号保持原状"}
        }
    }
}

@Composable
fun EmailScreen(repo: StudentRepository, back:()->Unit) {
    var email by rememberSaveable{mutableStateOf("")};var code by rememberSaveable{mutableStateOf("")};var challenge by rememberSaveable{mutableStateOf("")}
    var data by remember{mutableStateOf(JSONObject())};var loaded by remember{mutableStateOf(false)}
    var settings by remember{mutableStateOf(JSONObject())};var keywords by rememberSaveable{mutableStateOf("")};var categories by rememberSaveable{mutableStateOf("")}
    StudentPage("邮箱提醒","平台向你的收件邮箱发信，不需要提供 QQ 邮箱密码",back) {
        OnlineAction("读取绑定与发送状态"){
            data=repo.api("email");settings=data.optJSONObject("settings")?:JSONObject();email=data.optString("email")
            keywords=(settings.optJSONArray("keywords")?:JSONArray()).let{(0 until it.length()).joinToString(","){i->it.getString(i)}}
            categories=(settings.optJSONArray("categories")?:JSONArray()).let{(0 until it.length()).joinToString(","){i->it.getString(i)}}
            loaded=true;"已读取；发送记录中的 submitted 表示发信服务器已接收，仍可能被归入垃圾邮件"
        }
        StudentCard("更换邮箱先验证","验证成功前继续使用旧邮箱；验证成功后提醒默认关闭，由你主动开启。")
        OutlinedTextField(email,{email=it},label={Text("提醒收件邮箱")},modifier=Modifier.fillMaxWidth())
        OnlineAction("发送绑定验证码"){challenge=repo.api("email/code","POST",JSONObject().put("email",email)).getString("challenge");"请检查收件箱和垃圾箱"}
        if(challenge.isNotBlank()) {
            OutlinedTextField(code,{code=it.take(6)},label={Text("绑定验证码")},modifier=Modifier.fillMaxWidth())
            OnlineAction("验证新邮箱"){repo.api("email/verify","POST",JSONObject().put("challenge",challenge).put("code",code));settings=JSONObject();loaded=true;"已绑定，提醒尚未开启"}
        }
        if(loaded) {
            for((key,label) in listOf("enabled" to "邮箱提醒总开关","news" to "新竞赛摘要","deadline" to "竞赛截止提醒","admission" to "升学事项","custom" to "自定任务")) Row {
                Text(label,Modifier.weight(1f).padding(top=12.dp));Switch(settings.optBoolean(key),{settings=JSONObject(settings.toString()).put(key,it)})
            }
            StudentTabs(listOf("每天摘要","每小时摘要"),if(settings.optString("frequency")=="hourly")1 else 0){settings=JSONObject(settings.toString()).put("frequency",if(it==0)"daily" else "hourly")}
            OutlinedTextField(categories,{categories=it},label={Text("分类，逗号分隔；留空全部")},supportingText={Text("科技、创业、设计、外语、综合")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(keywords,{keywords=it},label={Text("关键词，逗号分隔；留空不限")},modifier=Modifier.fillMaxWidth())
            Text("分类组内满足任一，关键词组内满足任一；两组同时设置时需要同时满足。开启前的历史通知不补发。")
            OnlineAction("保存订阅设置"){
                val body=JSONObject(settings.toString()).put("frequency",settings.optString("frequency","daily"))
                    .put("categories",JSONArray(categories.replace('，',',').split(',').map{it.trim()}.filter{it.isNotBlank()}))
                    .put("keywords",JSONArray(keywords.replace('，',',').split(',').map{it.trim()}.filter{it.isNotBlank()}))
                repo.api("email","PUT",body);"订阅已在云端保存"
            }
            OnlineAction("发送测试邮件"){repo.api("email/test","POST").optString("message")}
            OnlineAction("解绑并停止邮件"){repo.api("email","DELETE");loaded=false;email="";"已解绑，排队中的邮件已取消；已经发出的邮件无法撤回"}
            (data.optJSONArray("queue")?:JSONArray()).objects().forEach{StudentCard(it.optString("subject"),it.optString("status")+" · "+it.optString("error").replace("null",""))}
        }
        Text("添加自定云端提醒",style=MaterialTheme.typography.titleMedium)
        EditFields(listOf("title" to "提醒事项","time" to "北京时间 YYYY-MM-DD HH:mm","type" to "custom / admission / deadline"),JSONObject().put("type","custom"),"保存到云端") { j->
            val due=LocalDateTime.parse(j.getString("time").replace(' ','T')).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()
            require(due>System.currentTimeMillis()/1000){"请选择未来时间"}
            repo.api("reminders/${UUID.randomUUID()}","PUT",JSONObject().put("title",j.getString("title")).put("due",due).put("category",j.getString("type")))
        }
        var reminders by remember{mutableStateOf<List<JSONObject>>(emptyList())}
        OnlineAction("查看已保存云端提醒"){reminders=repo.api("reminders").getJSONArray("items").objects();"已刷新"}
        reminders.forEach{r->StudentCard(r.optString("title"),"${java.time.Instant.ofEpochSecond(r.optLong("due")).atZone(ZoneId.of("Asia/Shanghai"))} · ${if(r.optInt("enabled")==1)"启用" else "关闭"}",actions={
            if(r.optInt("enabled")==1) OnlineAction("关闭此提醒"){repo.api("reminders/${r.getString("id")}","PUT",JSONObject().put("title",r.getString("title")).put("due",r.getDouble("due")).put("category",r.getString("category")).put("enabled",false));reminders=repo.api("reminders").getJSONArray("items").objects();"已关闭"}
        })}
    }
}

@Composable
fun CatalogScreen(repo: StudentRepository, team: (String)->Unit, notices: (String)->Unit) {
    var query by rememberSaveable{mutableStateOf("")};var list by remember{mutableStateOf<List<JSONObject>>(emptyList())};var selected by remember{mutableStateOf<JSONObject?>(null)};var message by remember{mutableStateOf("")}
    LaunchedEffect(Unit){
        list=runCatching{repo.context.assets.open("catalog.json").bufferedReader().use{JSONArray(it.readText()).objects()}}.getOrDefault(emptyList())
        runCatching{repo.api("catalog").getJSONArray("items").objects()}.onSuccess{list=it}.onFailure{message="正在使用内置 2023 年目录，联网后可刷新"}
    }
    StudentPage("竞赛目录","2023 年分析报告目录 · 名单按年份保存") {
        OutlinedTextField(query,{query=it},label={Text("搜索名称或别名")},modifier=Modifier.fillMaxWidth())
        OnlineAction("刷新目录"){list=repo.api("catalog").getJSONArray("items").objects();"已获取 ${list.size} 项"}
        if(message.isNotBlank())Text(message)
        Text("目录收录不等于本学院综测或推免认定。费用、赛制与报名时间请核对当年原文。")
        list.filter{it.optString("name").contains(query,true)||it.optString("aliases").contains(query,true)}.forEach{item->StudentCard("${item.optInt("number")} · ${item.optString("name")}","${item.optInt("year")} 年目录 · 点击查看来源与组队入口",{selected=item})}
    }
    selected?.let{j->AlertDialog(onDismissRequest={selected=null},title={Text(j.optString("name"))},text={Column{Text("目录年份：${j.optInt("year")}\n认定、费用、比赛安排：以当年主办方与学院规则为准");StudentLink("查看目录原文",j.optString("source"));TextButton(onClick={notices(j.getString("name"));selected=null}){Text("搜索相关通知")}}},confirmButton={TextButton(onClick={team(j.getString("name"));selected=null}){Text("查看相关组队")}},dismissButton={TextButton(onClick={selected=null}){Text("返回")}})}
}

@Composable
fun CommunityScreen(repo: StudentRepository, isTeam: Boolean) {
    var list by remember(isTeam){mutableStateOf<List<JSONObject>>(emptyList())};var query by rememberSaveable(isTeam){mutableStateOf("")}
    var selected by remember(isTeam){mutableStateOf<JSONObject?>(null)};var editor by remember{mutableStateOf(false)}
    var mine by remember{mutableStateOf(false)};var contest by rememberSaveable{mutableStateOf(repo.context.getSharedPreferences("student_filters",0).getString("contest","")?:"")}
    val path=if(isTeam)"teams" else "posts"
    val scope=rememberCoroutineScope();var message by remember{mutableStateOf("")}
    suspend fun refresh(){list=repo.api(if(isTeam&&mine)"teams/mine" else "$path?q=${java.net.URLEncoder.encode(query,"UTF-8")}&contest=${java.net.URLEncoder.encode(contest,"UTF-8")}").getJSONArray("items").objects()}
    LaunchedEffect(isTeam){runCatching{refresh()}.onFailure{message=it.message?:"加载失败"}}
    if(selected!=null) {
        if(isTeam) TeamDetailScreen(repo,selected!!.getString("id")){selected=null}
        else PostDetailScreen(repo,selected!!){selected=null}
        return
    }
    StudentPage(if(isTeam)"找队友" else "经验交流","真实学生发布 · 公开浏览，参与需要邮箱登录") {
        OutlinedTextField(contest,{contest=it},label={Text("关联赛事，留空查看全部")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(query,{query=it},label={Text("搜索")},modifier=Modifier.fillMaxWidth())
        if(isTeam) Row{Checkbox(mine,{mine=it});Text("我的队伍与申请")}
        OnlineAction("搜索 / 刷新"){refresh();"已刷新"}
        Button(onClick={editor=true}){Text(if(isTeam)"发布招募" else "分享经验")}
        if(message.isNotBlank())Text(message)
        if(list.isEmpty())StudentCard("暂无相关内容","可以调整筛选条件，或分享你自己的真实经历。")
        list.forEach{item->StudentCard(item.optString("title"),if(isTeam) "${item.optString("contest")}\n${item.optInt("members")}/${item.optInt("capacity")} 成员 · ${item.optInt("reserved")} 待确认 · ${item.optString("status")}" else "${item.optString("year")} · ${item.optString("contest")}\n${item.optString("body").take(130)}",{selected=item})}
    }
    if(editor) AlertDialog(onDismissRequest={editor=false},title={Text(if(isTeam)"发布招募" else "经验草稿")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("提交后会公开以下填写内容，请勿填写成绩单、学号或私人联系方式。")
        val fields=if(isTeam)listOf("contest" to "赛事名称","title" to "招募标题","track" to "赛道","capacity" to "队伍总人数（包含队长）","roles" to "所需角色和能力","schedule" to "每周投入、比赛时间与现有进度","goal" to "参赛目标","location" to "线上或校区") else listOf("contest" to "赛事名称","title" to "经验标题","year" to "参赛年份","body" to "过程 / 分工 / 工具 / 坑点 / 真实结果")
        EditFields(fields,JSONObject().put("contest",contest).put("capacity","3").put("year","2026"),"确认并公开发布",draftKey="community-$path"){data->
            if(isTeam)data.put("capacity",data.getString("capacity").toInt())
            repo.api(path,"POST",data);editor=false;refresh()
        }
    }},confirmButton={},dismissButton={TextButton(onClick={editor=false}){Text("取消")}})
}

@Composable
fun TeamDetailScreen(repo: StudentRepository, id: String, back:()->Unit) {
    var team by remember{mutableStateOf(JSONObject())};var loaded by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    var edit by remember{mutableStateOf(false)};var resultForm by remember{mutableStateOf(false)};var questionTo by remember{mutableStateOf("")}
    suspend fun refresh(){team=repo.api("teams/$id");loaded=true}
    LaunchedEffect(id){runCatching{refresh()}.onFailure{error=it.message?:"读取失败"}}
    StudentPage(team.optString("title","队伍详情"),"录用后由申请人确认，名额最多保留 48 小时",back) {
        if(error.isNotBlank())Text(error)
        OnlineAction("刷新申请状态"){refresh();"已刷新"}
        if(loaded){
            val data=team.optJSONObject("data")?:JSONObject()
            StudentCard("${team.optInt("members")}/${team.optInt("capacity")} 位成员", "赛道：${data.optString("track")}\n角色：${data.optString("roles")}\n安排：${data.optString("schedule")}\n目标：${data.optString("goal")}\n地点：${data.optString("location")}")
            val apps=(team.optJSONArray("applications")?:JSONArray()).objects()
            if(team.optBoolean("mine")) {
                TextButton(onClick={edit=!edit}){Text("修改招募内容")}
                if(edit)EditFields(listOf("contest" to "赛事","title" to "标题","track" to "赛道","capacity" to "总人数","roles" to "角色要求","schedule" to "时间与进度","goal" to "目标","location" to "地点"),data,"保存并通知申请人"){
                    it.put("capacity",it.getString("capacity").toInt());repo.api("teams/$id","PUT",it);edit=false;refresh()
                }
                for((action,label) in listOf("pause" to "暂停招募","resume" to "恢复招募","close" to "关闭空队伍")) OnlineAction(label){repo.api("teams/$id/action","POST",JSONObject().put("action",action));refresh();"状态已更新"}
                apps.forEach{a->val d=a.optJSONObject("data")?:JSONObject();StudentCard(d.optString("nickname"),"${d.optString("skills")}\n时间：${d.optString("availability")}\n理由：${d.optString("reason")}\n状态：${a.optString("status")}",actions={
                    TextButton(onClick={questionTo=a.getString("owner")}){Text("请对方补充信息")}
                    if(a.optString("status")=="pending")for((action,label) in listOf("offer" to "录用，等待本人确认","reject" to "婉拒"))OnlineAction(label){repo.api("teams/$id/action","POST",JSONObject().put("action",action).put("applicant",a.getString("owner")));refresh();"已处理"}
                    if(a.optString("status")=="member")OnlineAction("移交队长给此成员"){repo.api("teams/$id/action","POST",JSONObject().put("action","transfer").put("applicant",a.getString("owner")));refresh();"队长已移交，你仍是队员"}
                })}
                TextButton(onClick={resultForm=!resultForm}){Text("发起团队成果确认")}
                if(resultForm)EditFields(listOf("title" to "成果名称","result" to "真实结果","evidence" to "证明文件名称或链接"),JSONObject(),"发送给当前成员确认"){
                    repo.api("teams/$id/results","POST",it);resultForm=false
                }
            } else {
                val application=apps.firstOrNull()
                if(application==null||application.optString("status") in listOf("withdrawn","expired","rejected"))EditFields(listOf("nickname" to "公开昵称","skills" to "擅长技能","availability" to "可投入时间","reason" to "申请理由"),JSONObject(),"提交入队申请"){repo.api("teams/$id/apply","POST",it);refresh()}
                else {
                    Text("申请状态：${application.optString("status")}")
                    TextButton(onClick={questionTo=team.getString("owner")}){Text("向队长补充说明")}
                    val actions=when(application.optString("status")){"offered"->listOf("confirm" to "确认入队","withdraw" to "放弃录用");"pending"->listOf("withdraw" to "撤回申请");"member"->listOf("leave" to "退出队伍");else->emptyList()}
                    for((action,label)in actions)OnlineAction(label){repo.api("teams/$id/action","POST",JSONObject().put("action",action));refresh();"状态已更新"}
                }
            }
            if(questionTo.isNotBlank())EditFields(listOf("text" to "补充交流内容"),JSONObject(),"发送站内消息"){
                repo.api("teams/$id/question","POST",it.put("recipient",questionTo));questionTo=""
            }
            if(team.optBoolean("mine")||apps.any{it.optString("status")=="member"}) {
                Text("队内任务")
                (team.optJSONArray("tasks")?:JSONArray()).objects().forEach{t->StudentCard(t.optString("title"),"${t.optString("due")} · ${if(t.optBoolean("done"))"已完成" else "待完成"}",actions={OnlineAction(if(t.optBoolean("done"))"标记未完成" else "完成"){repo.api("teams/$id/tasks/${t.getString("id")}","PUT",JSONObject(t.toString()).put("done",!t.optBoolean("done")));refresh();"任务已更新"}})}
                EditFields(listOf("title" to "任务名称","due" to "截止日期 YYYY-MM-DD"),JSONObject(),"添加队内任务"){repo.api("teams/$id/tasks/${UUID.randomUUID()}","PUT",it);refresh()}
            }
        }
    }
}

@Composable
fun PostDetailScreen(repo: StudentRepository, post: JSONObject, back:()->Unit) {
    var comments by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var editing by remember{mutableStateOf(false)}
    StudentPage(post.optString("title"),"${post.optString("year")} · ${post.optString("contest")}",back) {
        Text(post.optString("body"))
        if(post.optString("owner")==repo.owner()){
            TextButton(onClick={editing=!editing}){Text("编辑我的经验")}
            if(editing)EditFields(listOf("contest" to "赛事名称","title" to "标题","year" to "参赛年份","body" to "正文"),post,"保存修改"){
                repo.api("posts/${post.getString("id")}","PUT",it);editing=false;back()
            }
        }else OnlineAction("屏蔽此作者"){
            repo.api("blocks","POST",JSONObject().put("target",post.getString("owner")).put("enabled",true));"已屏蔽，刷新经验列表后生效，可在消息中心解除"
        }
        for((kind,label)in listOf("helpful" to "这篇对我有帮助","favorite" to "收藏经验"))OnlineAction(label){repo.api("posts/${post.getString("id")}/reaction","POST",JSONObject().put("kind",kind).put("enabled",true));"已保存"}
        OnlineAction("读取评论"){comments=repo.api("posts/${post.getString("id")}/comments").getJSONArray("items").objects();"已读取"}
        comments.forEach{StudentCard("同学的回复",it.optString("body"))}
        EditFields(listOf("body" to "评论内容"),JSONObject(),"发表评论"){repo.api("posts/${post.getString("id")}/comments","POST",it);comments=repo.api("posts/${post.getString("id")}/comments").getJSONArray("items").objects()}
        EditFields(listOf("reason" to "举报原因：过期、侵权或不当内容"),JSONObject(),"提交举报"){repo.api("reports","POST",it.put("target","post:${post.getString("id")}"))}
    }
}

@Composable
fun InboxScreen(repo: StudentRepository, back:()->Unit) {
    var messages by remember{mutableStateOf<List<JSONObject>>(emptyList())};var reports by remember{mutableStateOf<List<JSONObject>>(emptyList())};var blocks by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var team by remember{mutableStateOf("")};var post by remember{mutableStateOf<JSONObject?>(null)};var result by remember{mutableStateOf<JSONObject?>(null)}
    if(team.isNotBlank()){TeamDetailScreen(repo,team){team=""};return}
    if(post!=null){PostDetailScreen(repo,post!!){post=null};return}
    StudentPage("消息与处理进度","不把每次点赞变成邮件；重要处理在这里查看",back) {
        OnlineAction("刷新消息与举报进度"){
            messages=repo.api("messages").getJSONArray("items").objects();reports=repo.api("reports").getJSONArray("items").objects();blocks=repo.api("blocks").getJSONArray("items").objects();"已刷新"
        }
        messages.forEach{m->StudentCard((if(m.optInt("seen")==0)"未读 · " else "")+m.optString("title"),m.optString("body"),actions={
            OnlineAction("查看并标记已读"){
                repo.api("messages/${m.getString("id")}/seen","POST")
                val link=m.optString("link");when{link.startsWith("team:")->team=link.substringAfter(':');link.startsWith("post:")->post=repo.api("posts/${link.substringAfter(':')}");link.startsWith("result:")->result=repo.api("team-results/${link.substringAfter(':')}")}
                messages=repo.api("messages").getJSONArray("items").objects();"已读"
            }
        })}
        result?.let{r->val data=r.getJSONObject("data");StudentCard("成果待确认：${data.optString("title")}","${data.optString("result")}\n证据：${data.optString("evidence")}\n只确认真实贡献；不会自动认定奖项或增加综测分数。")
            EditFields(listOf("role" to "我负责的角色","note" to "我实际完成的工作"),JSONObject(),"确认并保存我的经历"){
                val saved=repo.api("team-results/${r.getString("id")}/confirm","POST",it.put("accepted",true))
                repo.save("portfolio",saved.getJSONObject("data"),saved.getString("id"));result=null
            }
            TextButton(onClick={result=null}){Text("暂不确认")}
        }
        reports.forEach{StudentCard("我的举报 · ${it.optString("status")}",it.optString("reason"))}
        blocks.forEach{b->OnlineAction("解除屏蔽用户 ${b.getString("target").takeLast(6)}"){
            repo.api("blocks","POST",JSONObject().put("target",b.getString("target")).put("enabled",false));blocks=blocks.filter{it!=b};"已解除屏蔽"
        }}
    }
}
