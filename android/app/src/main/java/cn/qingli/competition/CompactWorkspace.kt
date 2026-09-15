package cn.qingli.competition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun CompactBack(title:String,back:()->Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回",tint=IosBlue)}
        Text(title,fontSize=21.sp,fontWeight=FontWeight.SemiBold)
    }
}

@Composable
fun WorkbenchHome(repo:StudentRepository,records:List<StudentRecord>,route:(String)->Unit) {
    val scope=rememberCoroutineScope()
    val tasks=records.filter{it.kind=="task" && !it.data().optBoolean("done")}.sortedBy{it.data().optString("due","9999")}
    StudentPage("") {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("工作台",fontSize=25.sp,fontWeight=FontWeight.Bold)
                Text("常用工具，一步直达",fontSize=12.sp,color=IosMuted)
            }
            IconButton(onClick={route("search_all")}){Icon(Icons.Outlined.Search,"搜索全部内容",tint=IosBlue)}
        }
        IosToolGrid(listOf(
            IosTool("综测系统",Icons.Outlined.School,"assessment"),
            IosTool("成绩分析",Icons.Outlined.BarChart,"grades"),
            IosTool("升学规划",Icons.Outlined.AccountBalance,"admission"),
            IosTool("日程提醒",Icons.Outlined.CalendarMonth,"calendar"),
            IosTool("简历优化",Icons.Outlined.Description,"resume"),
            IosTool("模拟面试",Icons.Outlined.RecordVoiceOver,"interview"),
            IosTool("资料库",Icons.Outlined.FolderOpen,"materials"),
            IosTool("成长档案",Icons.Outlined.Badge,"portfolio")
        ),open=route)
        val drafts=records.filter{it.kind=="draft"&&it.id in listOf("resume-studio","interview-studio")}
        if(drafts.isNotEmpty()) {
            IosSection("继续上次编辑")
            IosGroup { drafts.forEachIndexed{i,d -> IosRow(if(d.id=="resume-studio")"未完成的简历草稿" else "未完成的面试准备","已自动保存在本机",Icons.Outlined.History,divider=i<drafts.lastIndex){route(if(d.id=="resume-studio")"resume" else "interview")} } }
        }
        IosSection("今日安排","管理待办"){route("tasks")}
        IosGroup {
            if(tasks.isEmpty())Row(Modifier.padding(16.dp)){Text("今天还没有安排，添加一件想完成的事。",fontSize=13.sp,color=IosMuted)}
            tasks.take(5).forEach { task ->
                Row(Modifier.fillMaxWidth().padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(false,{scope.launch{repo.save("task",task.data().put("done",true),task.id)}})
                    Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                        Text(task.data().optString("title"),fontSize=14.sp,maxLines=2)
                        val due=task.data().optString("due")
                        if(due.isNotBlank())Text(due,fontSize=11.sp,color=IosMuted)
                    }
                }
            }
        }
        Text("智能处理进度集中在「消息 → AI任务」。",fontSize=12.sp,color=IosMuted)
    }
}

@Composable
fun AdmissionHub(records:List<StudentRecord>,route:(String)->Unit,back:()->Unit) {
    StudentPage("升学规划","${records.count{it.kind=="target"}} 所目标院校 · ${records.count{it.kind=="application"}} 条申请",back) {
        IosGroup {
            IosRow("目标院校","记录要求，对比学校与专业",Icons.Outlined.AccountBalance){route("targets")}
            IosRow("申请进度","批次、材料清单与截止日期",Icons.Outlined.FactCheck,divider=false){route("applications")}
        }
        StudentCard("检查申请准备情况","根据已保存目标，整理材料缺口和下一步安排。",actions={TextButton(onClick={route("agent_admission")}){Text("开始分析")}})
    }
}

@Composable
fun PublishPanel(records:List<StudentRecord>,choose:(String)->Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("你想发布什么？",fontSize=23.sp,fontWeight=FontWeight.Bold)
        Text("选择类型，按提示填写就好",fontSize=13.sp,color=IosMuted)
        IosGroup {
            IosRow("找队友","选赛事、写分工，找到合适的伙伴",Icons.Outlined.Group){choose("publish_team")}
            IosRow("发经验","分享备赛过程与踩坑心得",Icons.Outlined.EditNote){choose("publish_post")}
            IosRow("分享资料","上传文件，选择私有或公开",Icons.Outlined.UploadFile,divider=false){choose("publish_resource")}
        }
        IosRow("草稿箱","${records.count{it.kind=="draft"}} 份本机草稿",Icons.Outlined.Drafts,divider=false){choose("drafts")}
    }
}

@Composable
fun DraftList(records:List<StudentRecord>,route:(String)->Unit,back:()->Unit) {
    val drafts=records.filter{it.kind=="draft"}
    StudentPage("草稿箱","尚未公开的内容，仅保存在当前账号本机空间",back) {
        if(drafts.isEmpty())Text("还没有草稿")
        drafts.forEach { draft ->
            val destination=when(draft.id){"community-teams"->"publish_team";"community-posts"->"publish_post";"resume-studio"->"resume";"interview-studio"->"interview";else->"tasks"}
            val label=when(destination){"publish_team"->"组队招募";"publish_post"->"参赛经验";"resume"->"简历草稿";"interview"->"面试准备";else->"个人记录草稿"}
            StudentCard(draft.data().optString("title").ifBlank{label},draft.data().optString("body").ifBlank{draft.data().optString("text")}.take(100),{route(destination)})
        }
    }
}

@Composable
fun ExperienceGrid(posts:List<JSONObject>,open:(JSONObject)->Unit) {
    posts.chunked(2).forEachIndexed { rowIndex,pair ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            pair.forEachIndexed { index,post ->
                Surface(Modifier.weight(1f).iosClick{open(post)},shape=RoundedCornerShape(14.dp),color=Color.White) {
                    Column {
                        Column(Modifier.fillMaxWidth().background(if((rowIndex+index)%2==0)Color(0xFFE9F2FF) else Color(0xFFFFF3E5)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("参赛经验",fontSize=10.sp,color=IosMuted)
                            Text(post.optString("title"),fontSize=16.sp,lineHeight=23.sp,fontWeight=FontWeight.SemiBold,minLines=2,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(post.optString("body").replace('\n',' '),fontSize=12.sp,lineHeight=18.sp,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=IosMuted)
                            Text(post.optString("year")+" · "+post.optString("contest"),fontSize=10.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=IosBlue)
                        }
                    }
                }
            }
            if(pair.size==1)Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun MessageHub(repo:StudentRepository,records:List<StudentRecord>,notices:List<NoticeItem>,open:(NoticeItem)->Unit,route:(String)->Unit) {
    var section by rememberSaveable{mutableIntStateOf(0)}
    val scope=rememberCoroutineScope()
    val noticeRepo=remember{repo.context.repository()}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("消息",Modifier.weight(1f),fontSize=25.sp,fontWeight=FontWeight.Bold)
            if(section==0)TextButton(onClick={scope.launch{notices.forEach { n -> noticeRepo.editState(n.notice.id){it.copy(read=true)} }}}){Text("全部已读",fontSize=12.sp)}
        }
        StudentTabs(listOf("赛事提醒","组队互动","AI任务"),section){section=it}
        Box(Modifier.weight(1f)) {
            when(section) {
                0 -> LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(notices.isEmpty())item{StudentCard("还没有赛事消息","同步成功后，新的官网通知会出现在这里。")}
                    items(notices.take(100),key={it.notice.id}) { n ->
                        IosGroup { IosRow(n.notice.title,(if(n.state.read)"已读" else "未读")+" · "+n.notice.source,Icons.Outlined.NotificationsNone,divider=false){open(n)} }
                    }
                }
                1 -> InboxScreen(repo)
                2 -> AgentCenter(repo,records,route){section=0}
            }
        }
    }
}

@Composable
fun PersonalHome(repo:StudentRepository,records:List<StudentRecord>,notices:List<NoticeItem>,route:(String)->Unit) {
    val account by repo.account.collectAsStateWithLifecycle()
    val profile=records.firstOrNull{it.kind=="profile"}?.data()?:JSONObject()
    val scope=rememberCoroutineScope()
    StudentPage("我的") {
        Surface(color=Color(0xFFEAF2FF),shape=RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    IosIcon(Icons.Outlined.PersonOutline,size=52)
                    Column(Modifier.weight(1f)) {
                        Text(if(account.isBlank())"青理同学" else account.substringBefore('@'),fontSize=21.sp,fontWeight=FontWeight.Bold)
                        Text(profile.optString("school","青岛理工大学"),fontSize=12.sp,color=IosMuted)
                    }
                    TextButton(onClick={route("profile")}){Text("编辑资料",fontSize=12.sp)}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    IosMetric(notices.count{it.state.favorite}.toString(),"收藏通知",Modifier.weight(1f))
                    IosMetric(records.count{it.kind=="portfolio"}.toString(),"成长经历",Modifier.weight(1f))
                    IosMetric(records.count{it.kind=="task"&&!it.data().optBoolean("done")}.toString(),"待办事项",Modifier.weight(1f))
                }
            }
        }
        IosGroup {
            IosRow("我的收藏","收藏的通知可离线阅读",Icons.Outlined.BookmarkBorder){route("favorites")}
            IosRow("浏览记录","查看已经读过的通知",Icons.Outlined.History){route("history")}
            IosRow("我的发布","管理自己分享的竞赛经验",Icons.Outlined.EditNote,divider=false){route("my_posts")}
        }
        IosGroup {
            IosRow("账号与同步",if(account.isBlank())"邮箱登录，备份个人记录" else account,Icons.Outlined.AccountCircle){route("account")}
            IosRow("邮箱提醒","绑定收件邮箱，选择提醒偏好",Icons.Outlined.MailOutline){route("email")}
            IosRow("设置与连接","系统通知、服务地址与使用帮助",Icons.Outlined.Settings,divider=false){route("settings")}
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("操作成功时轻触反馈",Modifier.weight(1f),fontSize=13.sp)
            Switch(profile.optBoolean("haptics",true),{enabled->scope.launch{repo.save("profile",profile.put("haptics",enabled),"me")};repo.context.getSharedPreferences("student_feedback",0).edit().putBoolean("enabled",enabled).apply()})
        }
        Text("个人学习工具 · 非学校官方应用",fontSize=11.sp,color=IosMuted)
    }
}

@Composable
fun ContestSelector(initial:List<JSONObject>,selected:String,choose:(String)->Unit,repo:StudentRepository) {
    var query by rememberSaveable{mutableStateOf("")}
    var rows by remember(initial){mutableStateOf(initial)}
    var message by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    val visible=rows.filter{query.isBlank() || it.optString("name").contains(query,true) || it.optString("current_name").contains(query,true)}
    Column(Modifier.fillMaxHeight(.85f).padding(horizontal=16.dp)) {
        Text("按赛事查看通知",fontSize=23.sp,fontWeight=FontWeight.Bold)
        Text("84 项目录 · 选赛事筛选，点星标关注",fontSize=12.sp,color=IosMuted)
        OutlinedTextField(query,{query=it},placeholder={Text("搜索赛事名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
        TextButton(onClick={choose("")}){Text("查看全部赛事与学校通知")}
        if(message.isNotBlank())Text(message,fontSize=12.sp,color=IosBlue)
        LazyColumn(Modifier.weight(1f)) {
            items(visible,key={it.optString("id")}) { c ->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).iosClick{choose(c.getString("id"))}.padding(vertical=12.dp)) {
                        Text(c.optString("current_name").ifBlank{c.optString("name")},fontSize=14.sp,fontWeight=FontWeight.Medium,color=if(selected==c.optString("id"))IosBlue else IosInk)
                        val check=c.optJSONObject("check")
                        val sourceError=check?.optString("error").orEmpty().let{it.isNotBlank()&&it!="null"}
                        val collected=(check?.optDouble("success",0.0)?:0.0)>0.0
                        Text("${c.optInt("notice_count")} 条通知 · "+(if(collected&&sourceError)"本次未成功，保留已有内容" else if(collected)"已采集" else sourceStatus(c.optString("verification"))),fontSize=11.sp,color=IosMuted)
                        if(check?.optString("error").orEmpty().let{it.isNotBlank()&&it!="null"})Text(check!!.optString("error"),fontSize=10.sp,color=IosMuted,maxLines=2)
                    }
                    val following=c.optJSONObject("follow")!=null
                    IconButton(onClick={scope.launch{runCatching{repo.api("contests/${c.getString("id")}/follow","PUT",JSONObject().put("enabled",!following));rows=repo.api("contests").getJSONArray("items").objects();message=if(following)"已取消关注" else "已关注；邮件偏好在我的页面设置"}.onFailure{message=it.message?:"请先登录"}}}) {
                        Icon(if(following)Icons.Outlined.Star else Icons.Outlined.StarBorder,if(following)"取消关注" else "关注赛事",tint=if(following)IosBlue else IosMuted)
                    }
                }
                HorizontalDivider(thickness=.5.dp,color=IosSeparator)
            }
            if(rows.isEmpty())item{Text("暂时无法加载目录，请联网后重新打开。",Modifier.padding(16.dp),fontSize=13.sp)}
        }
    }
}
