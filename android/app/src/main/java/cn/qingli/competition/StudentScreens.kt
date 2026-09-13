package cn.qingli.competition

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

val StudentBlue = IosBlue
fun number(value: Double?) = value?.let { String.format(Locale.CHINA,"%.2f",it) } ?: "待确认"

@Composable
fun StudentPage(title: String, subtitle: String = "", back: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp), verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Spacer(Modifier.height(if(back==null) 3.dp else 0.dp))
        if (back != null) IconButton(onClick=back,modifier=Modifier.offset(x=(-12).dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回",tint=IosBlue) }
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
            if(title.isNotBlank())Text(title,fontSize=if(back==null)26.sp else 23.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,color=IosInk)
            if(subtitle.isNotBlank()) Text(subtitle,fontSize=12.sp,lineHeight=17.sp,color=IosMuted)
        }
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun StudentCard(title: String, text: String, onClick: (() -> Unit)? = null, actions: @Composable (() -> Unit)? = null) {
    Card(modifier=Modifier.fillMaxWidth().then(if(onClick!=null) Modifier.iosClick(onClick) else Modifier),
        shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(title,Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=15.sp,lineHeight=21.sp)
                if(onClick!=null)Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.padding(start=8.dp).size(18.dp),tint=Color(0xFFB6B6BC))
            }
            if(text.isNotBlank()) Text(text,fontSize=13.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            actions?.invoke()
        }
    }
}

@Composable
fun StudentTabs(labels: List<String>, selected: Int, change: (Int)->Unit) {
    val scrollable=labels.size>4
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).then(if(scrollable)Modifier.horizontalScroll(rememberScrollState()) else Modifier)
        .background(Color(0xFFE6E6EB),RoundedCornerShape(12.dp)).padding(3.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
        labels.forEachIndexed { i,label ->
            Surface(onClick={change(i)},modifier=if(scrollable)Modifier else Modifier.weight(1f),shape=RoundedCornerShape(9.dp),color=if(selected==i)Color.White else Color.Transparent,
                shadowElevation=if(selected==i)1.dp else 0.dp) {
                Box(Modifier.heightIn(min=48.dp).padding(horizontal=if(scrollable)14.dp else 8.dp,vertical=10.dp),contentAlignment=Alignment.Center) {
                    Text(label,fontSize=13.sp,fontWeight=if(selected==i)FontWeight.SemiBold else FontWeight.Normal,color=if(selected==i)IosInk else IosMuted)
                }
            }
        }
    }
}

@Composable
fun StudentWorkspace(tab: Int, notices: List<NoticeItem>, settings: Settings, openNotice: (NoticeItem)->Unit,
                     searchNotices: (String)->Unit,
                     feed: @Composable ()->Unit, assessment: @Composable ()->Unit, favorites: @Composable ()->Unit,
                     schedule: @Composable ()->Unit, preferences: @Composable ()->Unit) {
    val context = LocalContext.current
    val repository = remember { context.students() }
    val records by repository.records.collectAsStateWithLifecycle(emptyList())
    var route by rememberSaveable(tab) { mutableStateOf("") }
    var section by rememberSaveable(tab) { mutableIntStateOf(0) }
    BackHandler(route.isNotEmpty()) { route="" }
    when(route) {
        "profile" -> ProfileScreen(repository,records) { route="" }
        "tasks" -> RecordScreen(repository,records,"task","统一待办",listOf("title" to "任务名称","due" to "日期 YYYY-MM-DD","source" to "来源：自定 / 竞赛 / 升学","note" to "具体要做什么")) { route="" }
        "calendar" -> UnifiedCalendarScreen(records,notices,openNotice,{route="tasks"}) { route="" }
        "favorites" -> Column { TextButton(onClick={route=""}) { Text("返回我的") }; favorites() }
        "settings" -> Column { TextButton(onClick={route=""}) { Text("返回我的") }; preferences() }
        "email" -> EmailScreen(repository) { route="" }
        "account" -> AccountScreen(repository,records) { route="" }
        "inbox" -> InboxScreen(repository) { route="" }
        "materials", "files", "resources", "print" -> MaterialHub(repository,when(route){"files"->1;"print"->2;else->0}) { route="" }
        "search_all" -> WorkspaceSearch(repository,records,notices,openNotice,{route=it}){route=""}
        "agents" -> AgentCenter(repository,records,{route=it}){route=""}
        "agent_contest", "agent_team", "agent_experience", "agent_print" -> GeneralAgentWorkspace(repository,route.removePrefix("agent_"),"智能任务") {route=""}
        "resume" -> ResumeWorkspace(repository,records) { route="" }
        "interview" -> InterviewWorkspace(repository,records) { route="" }
        "agent_study" -> GeneralAgentWorkspace(repository,"study","学业助手",JSONObject().put("grades",JSONArray(records.filter{it.kind=="grade"}.map{it.data()})).toString()) {route=""}
        "agent_admission" -> GeneralAgentWorkspace(repository,"admission","升学助手",records.filter{it.kind=="target"||it.kind=="application"}.joinToString("\n"){it.json}) {route=""}
        "agent_schedule" -> GeneralAgentWorkspace(repository,"schedule","日程助手",records.filter{it.kind=="task"}.joinToString("\n"){it.json}) {route=""}
        "agent_resource" -> GeneralAgentWorkspace(repository,"resource","资料助手") {route=""}
        "agent_portfolio" -> GeneralAgentWorkspace(repository,"portfolio","档案助手",records.filter{it.kind=="portfolio"}.joinToString("\n"){it.json}) {route=""}
        "targets" -> TargetsScreen(repository,records) { route="" }
        "applications" -> RecordScreen(repository,records,"application","申请进度",listOf("title" to "学校 / 项目 / 批次","due" to "截止日期 YYYY-MM-DD","status" to "准备中 / 已提交 / 等待 / 面试 / 结束","materials" to "材料清单与文件版本","url" to "官方来源网址","note" to "待补事项")) { route="" }
        else -> when(tab) {
            0 -> IosHome(records,notices,{route=it},openNotice)
            1 -> Column {
                StudentTabs(listOf("成绩","综测","成长档案"),section){section=it}
                when(section) {
                    0 -> Column { TextButton(onClick={route="agent_study"}){Text("分析课程并制定学习计划")}; GradesScreen(repository,records) }
                    1 -> assessment()
                    else -> RecordScreen(repository,records,"portfolio","成长档案",listOf("title" to "经历名称","type" to "竞赛 / 项目 / 证书 / 实践","date" to "发生日期 YYYY-MM-DD","role" to "本人职责","result" to "成果（仅填写真实内容）","evidence" to "证明文件名称与版本","note" to "具体贡献与过程"))
                }
            }
            2 -> Column {
                StudentTabs(listOf("通知","竞赛目录","找队友","经验交流"),section){section=it}
                when(section) { 0 -> feed(); 1 -> ContestWorkspace(repository); else -> CommunityScreen(repository,section==2) }
            }
            3 -> IosAdmission(records){route=it}
            4 -> IosProfile(repository,records){route=it}
        }
    }
}

@Composable
fun ProfileScreen(repo: StudentRepository, records: List<StudentRecord>, back:()->Unit) {
    val profile=records.find{it.kind=="profile"}?.data() ?: JSONObject().put("school","青岛理工大学")
    StudentPage("我的方向","更换目标不会删除成绩、经历和原有计划",back) {
        EditFields(listOf("school" to "学校","college" to "学院","major" to "专业","year" to "入学年份","semester" to "当前学期","goal" to "保研 / 考研 / 双路线 / 暂不确定"),profile,"保存到本机") { repo.save("profile",it,"me") }
    }
}

@Composable
fun EditFields(fields: List<Pair<String,String>>, initial: JSONObject, button: String="保存", draftKey: String="", save: suspend (JSONObject)->Unit) {
    var values by remember(initial.toString()) { mutableStateOf(fields.associate{it.first to initial.optString(it.first)}) }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope(); val view=LocalView.current; val context=LocalContext.current
    val draftRepo=remember{context.students()}
    LaunchedEffect(draftKey){if(draftKey.isNotBlank())draftRepo.dao.get("draft",draftKey)?.data()?.let{saved->values=fields.associate{it.first to saved.optString(it.first)}}}
    fields.forEach{(key,label)->OutlinedTextField(values[key]?:"",{values=values+(key to it)},label={Text(label)},modifier=Modifier.fillMaxWidth(),minLines=if(key in listOf("note","body","text","materials","requirements")) 3 else 1)}
    if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.primary)
    Button(enabled=!busy,onClick={scope.launch {
        busy=true
        try {
            val data=JSONObject(initial.toString()); values.forEach{(k,v)->data.put(k,v.trim())}
            require(values.values.any{it.isNotBlank()}) { "请先填写内容" }
            if(values.containsKey("title")) require(!values["title"].isNullOrBlank()) {"请填写名称"}
            for(k in listOf("due","date")) if(!values[k].isNullOrBlank()) require(runCatching{LocalDate.parse(values[k])}.isSuccess){"日期请填写 YYYY-MM-DD，例如 2026-10-01"}
            if(!values["url"].isNullOrBlank()) require(Uri.parse(values["url"]).scheme in listOf("http","https")){"来源请填写 http 或 https 网址"}
            save(data); if(draftKey.isNotBlank())withContext(kotlinx.coroutines.NonCancellable){draftRepo.dao.delete("draft",draftKey)};message="已保存"
            if(context.getSharedPreferences("student_feedback",0).getBoolean("enabled",true)) view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        } catch(e:Exception){message=e.message?:"保存失败，请重试"} finally{busy=false}
    }},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(busy)"正在保存…" else button)}
    if(draftKey.isNotBlank())OutlinedButton(enabled=!busy,onClick={scope.launch{val data=JSONObject(initial.toString());values.forEach{(k,v)->data.put(k,v)};draftRepo.save("draft",data,draftKey);message="草稿已保存在本机，下次打开会恢复"}}){Text("仅保存本机草稿")}
}

@Composable
fun RecordScreen(repo: StudentRepository, all: List<StudentRecord>, kind: String, title: String, fields: List<Pair<String,String>>, back: (()->Unit)?=null) {
    var editing by remember { mutableStateOf<StudentRecord?>(null) }; var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<StudentRecord?>(null) }
    val scope=rememberCoroutineScope()
    StudentPage(title,"本机保存 · 可以修改和导出 · 云端备份需在账号页主动操作",back) {
        Button(onClick={adding=true}) { Text("添加${if(kind=="portfolio")"经历" else "记录"}") }
        val records=all.filter{it.kind==kind}.let{ if(kind=="task") it.sortedWith(compareBy({r->r.data().optBoolean("done")},{r->r.data().optString("due","9999")})) else it }
        if(records.isEmpty()) StudentCard("还没有记录","点击添加，从一件具体的事情开始。")
        records.forEach{record -> val j=record.data()
            StudentCard(j.optString("title"),fields.drop(1).mapNotNull{(k,label)->j.optString(k).takeIf{it.isNotBlank()}?.let{"${label.substringBefore(' ')}：$it"}}.joinToString("\n"),actions={
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if(kind=="task") TextButton(onClick={scope.launch{repo.save(kind,j.put("done",!j.optBoolean("done")),record.id)}}){Text(if(j.optBoolean("done"))"已完成 · 撤回" else "完成任务")}
                    TextButton(onClick={editing=record}){Text("编辑")}; TextButton(onClick={deleting=record}){Text("删除")}
                    if(j.optString("url").isNotBlank())StudentLink("官方来源",j.optString("url"))
                }
            })
        }
        TextExportButton(title,records.joinToString("\n\n"){r->fields.joinToString("\n"){(k,label)->"$label：${r.data().optString(k)}"}})
    }
    if(adding||editing!=null) AlertDialog(onDismissRequest={adding=false;editing=null},title={Text("编辑${title}")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            EditFields(fields,editing?.data()?:JSONObject(),draftKey="record-$kind-${editing?.id?:"new"}") { data-> repo.save(kind,data,editing?.id?:UUID.randomUUID().toString());adding=false;editing=null }
        }
    },confirmButton={},dismissButton={TextButton(onClick={adding=false;editing=null}){Text("取消")}})
    deleting?.let{record-> AlertDialog(onDismissRequest={deleting=null},title={Text("删除这条记录？")},text={Text("删除本机记录不会删除已导出的文件。")},confirmButton={TextButton(onClick={scope.launch{repo.dao.delete(kind,record.id);deleting=null}}){Text("删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("保留")}}) }
}

@Composable
fun StudentLink(label:String,url:String) {
    val context=LocalContext.current
    TextButton(onClick={runCatching{val uri=Uri.parse(url);require(uri.scheme in listOf("http","https"));context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,uri))}.onFailure{android.widget.Toast.makeText(context,"链接无法打开，请检查地址",android.widget.Toast.LENGTH_SHORT).show()}}){Text(label)}
}

@Composable
fun TargetsScreen(repo:StudentRepository,records:List<StudentRecord>,back:()->Unit) {
    var compare by remember{mutableStateOf(false)};var selected by remember{mutableStateOf<Set<String>>(emptySet())}
    val targets=records.filter{it.kind=="target"}
    if(!compare)Column{
        TextButton(onClick={compare=true}){Text("选择 3—5 个目标进行对比")}
        RecordScreen(repo,records,"target","目标院校",listOf("title" to "学校 / 学院 / 专业","route" to "保研 / 考研 / 双路线","year" to "入学年份","priority" to "优先级","subjects" to "考试或考核科目","duration" to "学制","requirements" to "申请要求","fees" to "学费与奖助（以官方来源为准）","url" to "官方来源网址","checked" to "核对日期 YYYY-MM-DD","note" to "个人备注（与官方信息区分）"),back)
    }else StudentPage("目标对比","只比较已填写信息，不生成录取概率",{compare=false}){
        targets.forEach{r->Row{Checkbox(r.id in selected,{if(it&&selected.size<5)selected=selected+r.id else if(!it)selected=selected-r.id});Text(r.data().optString("title"))}}
        if(selected.size<3)Text("请至少选择 3 个目标；不足时可先返回补充院校。")
        else for((key,label)in listOf("year" to "入学年份","route" to "申请路线","subjects" to "考核科目","duration" to "学制","requirements" to "申请要求","fees" to "学费奖助","checked" to "来源核对日期"))StudentCard(label,targets.filter{it.id in selected}.joinToString("\n\n"){"${it.data().optString("title")}\n${it.data().optString(key).ifBlank{"待补充，不能视为没有要求"}}"})
    }
}

@Composable
fun UnifiedCalendarScreen(records:List<StudentRecord>,notices:List<NoticeItem>,open:(NoticeItem)->Unit,tasks:()->Unit,back:()->Unit) {
    val today=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
    data class Event(val title:String,val date:LocalDate,val source:String,val ended:Boolean,val notice:NoticeItem?)
    val entries=notices.filter{it.state.favorite||it.state.reminder}.mapNotNull{n->n.deadlineMillis?.let{Event(n.notice.title,java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate(),if(n.state.customDeadline==null)"竞赛官方截止" else "竞赛自定日期",false,n)}}+
        records.filter{it.kind in listOf("task","application")}.mapNotNull{r->runCatching{val d=r.data();Event(d.optString("title"),LocalDate.parse(d.optString("due")),if(r.kind=="application")"升学申请" else "自定任务",d.optBoolean("done")||d.optString("status")=="结束",null)}.getOrNull()}
    StudentPage("统一日程","事项截止与通知提醒时间分开看；来源标在每张卡片上",back){
        OutlinedButton(onClick=tasks){Text("管理学习与自定任务")}
        for(group in listOf("今天","本周","之后","已结束")){
            val rows=entries.filter{e->val actual=when{e.ended||e.date<today->"已结束";e.date==today->"今天";e.date<=today.plusDays(7)->"本周";else->"之后"};actual==group}.sortedBy{it.date}
            if(rows.isNotEmpty()){Text(group,style=MaterialTheme.typography.titleMedium);rows.forEach{e->StudentCard(e.title,"${e.date} · ${e.source}",{if(e.notice!=null)open(e.notice) else tasks()})}}
        }
        if(entries.isEmpty())StudentCard("日程还没有事项","收藏有截止日期的比赛，或给学习任务填写日期，就会出现在这里。")
    }
}

@Composable
fun TextExportButton(title: String, text: String) {
    val context=LocalContext.current; val scope=rememberCoroutineScope(); var message by remember{mutableStateOf("")}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->if(uri!=null)scope.launch{message=runCatching{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray())}?:error("无法写入")};"已导出"}.getOrElse{it.message?:"导出失败"}}}
    OutlinedButton(onClick={launcher.launch("$title.txt")},enabled=text.isNotBlank()){Text("导出文字清单")}; if(message.isNotBlank())Text(message)
}

@Composable
fun GradesScreen(repo: StudentRepository, records: List<StudentRecord>) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val grades=records.filter{it.kind=="grade"}.map{Grade.from(it.data())}
    var selectedTerm by remember{mutableStateOf("全部")};var draft by rememberSaveable{mutableStateOf("")}
    var preview by remember{mutableStateOf<List<Grade>?>(null)};var error by remember{mutableStateOf("")}; var showImport by remember{mutableStateOf(false)}
    var target by rememberSaveable{mutableStateOf("85")};var future by rememberSaveable{mutableStateOf("20")}
    var trialCourse by rememberSaveable{mutableStateOf("")};var trialScore by rememberSaveable{mutableStateOf("")}
    var showCampus by remember{mutableStateOf(false)}; var campusAccount by remember{mutableStateOf("")};var campusPassword by remember{mutableStateOf("")};var campusConsent by remember{mutableStateOf(false)}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{
        runCatching{withContext(Dispatchers.IO){readBounded(context,uri,1_000_000).toString(Charsets.UTF_8)}}.onSuccess{draft=it;showImport=true}.onFailure{error=it.message?:"读取失败"}
    }}
    StudentPage("成绩与课程分析","原成绩单请保留；缺失的绩点不按猜测公式补齐") {
        val chosen=grades.filter{selectedTerm=="全部"||it.semester==selectedTerm};val summary=summarizeGrades(chosen)
        if(grades.isNotEmpty())StudentTabs(listOf("全部")+grades.map{it.semester}.distinct().sorted(),(listOf("全部")+grades.map{it.semester}.distinct().sorted()).indexOf(selectedTerm).coerceAtLeast(0)){ selectedTerm=(listOf("全部")+grades.map{it.semester}.distinct().sorted())[it] }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            IosMetric(summary.average?.let{number(it)}?:"—","加权成绩",Modifier.weight(1f))
            IosMetric(summary.gpa?.let{number(it)}?:"—","平均绩点",Modifier.weight(1f),IosInk)
        }
        Text("计分 ${number(summary.credits)} 学分 · 绩点覆盖 ${number(summary.gpaCredits)} 学分\n${summary.excluded} 门待发布或特殊状态课程未计入",fontSize=12.sp,lineHeight=19.sp,color=IosMuted)
        var showFormula by remember { mutableStateOf(false) }
        TextButton(onClick={showFormula=!showFormula},contentPadding=PaddingValues(horizontal=0.dp)) { Text(if(showFormula)"收起计算口径" else "查看计算口径",fontSize=13.sp) }
        if(showFormula)StudentCard("计算口径","Σ(分数×学分)÷Σ学分。绩点使用原表提供值按学分加权。补考、重修、免修暂排除，核对学校规则后在导入预览中调整。")
        OutlinedButton(onClick={showCampus=!showCampus}) { Text("从本人教务账号读取预览") }
        if(showCampus) {
            Text("先在综测页登录学生身份。此次只读取本人完整成绩，密码不保存在本机工作台；遇到学校验证码时改用文件导入。")
            OutlinedTextField(campusAccount,{campusAccount=it},label={Text("本人学号")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(campusPassword,{campusPassword=it},label={Text("教务密码（仅本次使用）")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Row { Checkbox(campusConsent,{campusConsent=it}); Text("同意通过现有综测后端读取本人的教务成绩，先预览再确认") }
            if(campusConsent) OnlineAction("读取成绩单预览") {
                draft=context.repository().transcriptPreview(campusAccount,campusPassword); campusPassword="";showImport=true
                runCatching{parseGradeCsv(draft)}.onSuccess{preview=it}.onFailure{error="有字段需要手动核对，请在 CSV 中修订：${it.message}"}
                "已读取，尚未保存或覆盖成绩"
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick={import.launch(arrayOf("text/*","application/csv","application/octet-stream"))}){Text("导入 CSV")}; OutlinedButton(onClick={showImport=!showImport}){Text("粘贴 / 修订")} }
        if(records.any{it.kind=="draft"&&it.id=="grade-import-backup"})OnlineAction("撤销上一次成绩导入") {repo.dao.undoGradeImport();"已恢复导入前的课程，本次新增课程已撤回"}
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        if(showImport) {
            Text("表头：学期,课程,学分,成绩,绩点,状态\n状态：正常 / 未发布 / 补考 / 重修 / 免修 / 不计入。缺失成绩或绩点留空。",fontSize=12.sp)
            OutlinedTextField(draft,{draft=it},label={Text("CSV 内容，可核对后手动修订")},modifier=Modifier.fillMaxWidth(),minLines=5)
            Button(onClick={runCatching{parseGradeCsv(draft)}.onSuccess{preview=it;error=""}.onFailure{error=it.message?:"格式不正确"}}){Text("预览差异")}
        }
        preview?.let{incoming->
            val existing=grades.associateBy{it.semester to it.course}
            val added=incoming.count{(it.semester to it.course) !in existing};val changed=incoming.count{existing[it.semester to it.course]?.let{old->old!=it}?:false}
            StudentCard("导入预览：新增 $added · 变化 $changed · 未变 ${incoming.size-added-changed}",incoming.joinToString("\n"){"${it.semester} ${it.course}：${it.score?:"未发布"} / ${it.credits} 学分 / 绩点 ${it.gpa?:"待确认"}"},actions={
                Button(onClick={scope.launch{
                    repo.dao.mergeGrades(incoming.map{g->val key=UUID.nameUUIDFromBytes((g.semester+"\u0000"+g.course).toByteArray()).toString();StudentRecord("grade",key,g.json().toString())})
                    repo.save("draft",JSONObject().put("title","成绩导入原文").put("text",draft)); preview=null;showImport=false;error="已确认保存，未在本次文件中出现的课程继续保留"
                }}){Text("确认合并，不删除其他课程")}
                TextButton(onClick={preview=null}){Text("返回修订")}
            })
        }
        if(grades.isNotEmpty()){
            StudentCard("学期变化",grades.groupBy{it.semester}.toSortedMap().map{(term,list)->val s=summarizeGrades(list);"$term：加权 ${number(s.average)} / 绩点 ${number(s.gpa)} / ${number(s.credits)} 学分"}.joinToString("\n"))
            Text("逐门课程",fontWeight=FontWeight.Bold)
            chosen.sortedByDescending{it.credits}.forEach{g-> StudentCard(g.course,"${g.semester} · ${g.status}\n成绩 ${g.score?:"待确认"} · 学分 ${g.credits} · 绩点 ${g.gpa?:"待确认"}\n${if(g.included)"在本次统计中的权重 ${number(g.credits/summary.credits*100)}%" else "本次不计入"}") }
            Text("目标测算（独立试算，不改动成绩）",fontWeight=FontWeight.Bold)
            OutlinedTextField(target,{target=it},label={Text("目标加权平均分")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(future,{future=it},label={Text("未来计分学分")},modifier=Modifier.fillMaxWidth())
            val overall=summarizeGrades(grades)
            val required=runCatching{targetAverage(overall.average?:error(""),overall.credits,future.toDouble(),target.toDouble())}.getOrNull()
            Text(if(required==null)"请填写有效目标和学分" else if(required>100)"需要 ${number(required)} 分，按当前学分假设无法实现，请调整目标或学分" else "未来课程需要达到平均 ${number(required.coerceAtLeast(0.0))} 分；不代表录取概率")
            OutlinedButton(enabled=required!=null,onClick={scope.launch{repo.save("scenario",JSONObject().put("target",target).put("credits",future).put("required",required))}}){Text("保存此次试算")}
            Text("单门课程试算",fontWeight=FontWeight.Bold)
            Text("选择已计入的课程，看看单科分数变化对加权成绩的影响；不会改动原成绩。",fontSize=12.sp)
            val candidates=grades.filter{it.included}
            val options=candidates.map{"${it.semester} ${it.course}"}
            StudentTabs(options,options.indexOf(trialCourse)){trialCourse=options[it]}
            OutlinedTextField(trialScore,{trialScore=it},label={Text("假设这门课取得多少分（0—100）")},modifier=Modifier.fillMaxWidth())
            val chosenIndex=options.indexOf(trialCourse);val score=trialScore.toDoubleOrNull()?.takeIf{it.isFinite()&&it in 0.0..100.0}
            val trial=candidates.getOrNull(chosenIndex)
            if(trial!=null&&score!=null){
                val after=summarizeGrades(grades.map{if(it.semester==trial.semester&&it.course==trial.course)it.copy(score=score) else it}).average
                StudentCard("试算后加权成绩 ${number(after)}","原加权成绩 ${number(overall.average)}，变化 ${number(after!!-overall.average!!)} 分。绩点仍需按学校规则另行核对。")
                OnlineAction("保存单科试算"){repo.save("scenario",JSONObject().put("title",trialCourse).put("original",trial.score).put("assumed",score).put("average",after));"试算已独立保存"}
            }
        }
    }
}
