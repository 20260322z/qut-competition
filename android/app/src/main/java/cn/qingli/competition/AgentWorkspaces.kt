package cn.qingli.competition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

val InterviewGreen=Color(0xFF16796C)

@Composable
fun AgentHeader(title:String,subtitle:String,back:()->Unit,green:Boolean=false) {
    Row(Modifier.fillMaxWidth().padding(end=16.dp,top=4.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回",tint=if(green)InterviewGreen else IosBlue)}
        Column(Modifier.weight(1f)){
            Text(title,fontSize=23.sp,fontWeight=FontWeight.Bold)
            Text(subtitle,fontSize=12.sp,color=IosMuted)
        }
        Icon(if(green)Icons.Outlined.RecordVoiceOver else Icons.Outlined.Description,null,tint=if(green)InterviewGreen else IosBlue)
    }
}

fun JSONArray.strings()=(0 until length()).map{optString(it)}
fun agentStatus(value:String)=mapOf("queued" to "等待处理","running" to "正在处理","waiting" to "等待你补充","completed" to "已完成","failed" to "可重试","cancelled" to "已取消","pending" to "尚未开始")[value]?:value

fun agentReport(run:JSONObject)=buildString{
    val output=run.optJSONObject("output")?:JSONObject()
    append(run.optString("title"));append("\n\n");append(output.optString("summary"))
    for(field in listOf("analysis","audit"))if(output.optString(field).isNotBlank())append("\n\n"+output.optString(field))
    for(field in listOf("strengths","improvements","warnings","questions"))output.optJSONArray(field)?.strings()?.forEach{append("\n\n• $it")}
    output.optJSONArray("sections")?.objects()?.forEach{append("\n\n${it.optString("title")}\n${it.optString("body")}")}
    output.optJSONArray("suggestions")?.objects()?.forEach{append("\n\n原文：${it.optString("before")}\n建议：${it.optString("after")}\n原因：${it.optString("reason")}")}
    output.optJSONArray("citations")?.objects()?.forEach{append("\n\n出处：${it.optString("name")}，第 ${it.optInt("page")} 页或段\n${it.optString("quote")}")}
    output.optJSONArray("tasks")?.objects()?.forEach{append("\n\n待确认事项：${it.optString("title")}\n${it.optString("note")}\n计划日期：${it.optString("due").ifBlank{"未设置"}}")}
    run.optJSONArray("turns")?.objects()?.forEach{append("\n\n第 ${it.optInt("turn")+1} 题：${it.optString("question")}\n本人回答：${it.optString("answer")}\n反馈：${it.optString("feedback")}")}
}

@Composable
fun ResumeWorkspace(repo:StudentRepository,records:List<StudentRecord>,back:()->Unit) {
    val draft=records.firstOrNull{it.kind=="draft"&&it.id=="resume-studio"}?.data()
    var page by rememberSaveable{mutableIntStateOf(0)}
    var text by rememberSaveable{mutableStateOf(draft?.optString("text")?:records.firstOrNull{it.kind=="resume"}?.data()?.optString("text")?:"")}
    var target by rememberSaveable{mutableStateOf(draft?.optString("target")?:"")}
    var mode by rememberSaveable{mutableIntStateOf(0)}
    var selected by rememberSaveable{mutableStateOf("")}
    var message by remember{mutableStateOf("")}
    var initialRun by rememberSaveable{mutableStateOf("")}
    val versions=records.filter{it.kind=="resume"}
    LaunchedEffect(draft?.optString("text")){if(text.isBlank()&&target.isBlank()){text=draft?.optString("text")?:"";target=draft?.optString("target")?:""}}
    LaunchedEffect(text,target){delay(700);if(text.isNotBlank()||target.isNotBlank())repo.save("draft",JSONObject().put("text",text).put("target",target),"resume-studio")}
    Column(Modifier.fillMaxSize()) {
        AgentHeader("简历工作室","材料 · 诊断 · 修改 · 版本",back)
        StudentTabs(listOf("工作台","编辑","智能任务","版本"),page){page=it}
        when(page){
            0 -> StudentPage(""){
                Surface(color=Color(0xFFEAF2FF),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                        Text("把真实经历，讲得更清楚",fontSize=20.sp,fontWeight=FontWeight.Bold)
                        Text("先核对材料，再诊断和修改。每段建议都保留原文，采用后仍可编辑。",fontSize=13.sp,color=IosMuted)
                        Button(onClick={page=1}){Text(if(text.isBlank())"开始准备简历" else "继续编辑草稿")}
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    IosMetric(versions.size.toString(),"保存版本",Modifier.weight(1f))
                    IosMetric(records.count{it.kind=="portfolio"}.toString(),"个人经历",Modifier.weight(1f))
                    IosMetric(text.length.toString(),"草稿字数",Modifier.weight(1f))
                }
                IosSection("准备一份可提交的简历")
                IosGroup{
                    IosRow("1 选择用途与材料","从本人经历开始，避免重复填写",Icons.Outlined.FolderOpen){page=1}
                    IosRow("2 智能诊断与补充","助手先找缺口，再向你确认事实",Icons.Outlined.AutoAwesome){page=2}
                    IosRow("3 比较建议并导出","逐段采用，保留原版本",Icons.Outlined.FileDownload,divider=false){page=3}
                }
                if(versions.isNotEmpty())StudentCard("最近版本",versions.first().data().optString("title"),onClick={page=3})
            }
            1 -> StudentPage("", "草稿自动保存在本机，云端处理需另行确认"){
                OutlinedTextField(target,{target=it},label={Text("目标项目与用途")},placeholder={Text("例如 数据科学夏令营，一页中文简历")},modifier=Modifier.fillMaxWidth())
                Text("引用个人经历",fontWeight=FontWeight.SemiBold)
                if(records.none{it.kind=="portfolio"})Text("可先粘贴正文，或在学业的成长档案中记录经历。",fontSize=12.sp,color=IosMuted)
                records.filter{it.kind=="portfolio"}.forEach{r->
                    val ids=selected.split('|').filter{it.isNotBlank()}.toSet()
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(r.id in ids,{checked->selected=(if(checked)ids+r.id else ids-r.id).joinToString("|")});Text(r.data().optString("title"),fontSize=13.sp)}
                }
                OutlinedButton(enabled=selected.isNotBlank(),onClick={
                    val chosen=selected.split('|');text+=(if(text.isBlank())"" else "\n\n")+records.filter{it.id in chosen}.joinToString("\n\n"){r->val j=r.data();"${j.optString("title")}\n职责：${j.optString("role")}\n贡献：${j.optString("note")}\n结果：${j.optString("result")}"};selected=""
                }){Text("将所选经历加入草稿")}
                OutlinedTextField(text,{text=it},label={Text("简历正文")},modifier=Modifier.fillMaxWidth(),minLines=10)
                OnlineAction("保存为本机新版本"){
                    require(text.isNotBlank()){ "请先填写简历正文" }
                    repo.save("resume",JSONObject().put("title",target.ifBlank{"我的简历"}).put("text",text));"新版本已保存，原版本保留"
                }
                Button(onClick={page=2}){Text("进入智能诊断")}
                DocumentExportButtons(target.ifBlank{"简历"},text)
            }
            2 -> AgentWorkbench(repo,"resume",text,target,initialRun,{
                initialRun=it
            },onApplySuggestion={before,after->
                if(before.isNotBlank()&&text.windowed(before.length).count{it==before}==1){text=text.replaceFirst(before,after);message="已采用到编辑草稿，可继续修改"}
                else message="当前草稿与任务原文不同或有重复段落，请在编辑页手动核对"
                message
            },extra={
                StudentTabs(listOf("全面优化","结构","目标适配","精简"),mode){mode=it}
                if(message.isNotBlank())Text(message,color=IosBlue,fontSize=13.sp)
                TextButton(onClick={page=1}){Text("返回编辑草稿")}
            },mode=listOf("全面优化","结构调整","目标适配","长度压缩")[mode])
            else -> StudentPage("","载入版本会作为编辑副本，不会覆盖历史版本"){
                if(versions.isEmpty())StudentCard("还没有保存版本","在编辑页保存后，可以比较和导出。",onClick={page=1})
                versions.forEach{r->StudentCard(r.data().optString("title"),"${java.time.Instant.ofEpochMilli(r.updated).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}\n${r.data().optString("text").take(160)}",actions={
                    TextButton(onClick={text=r.data().optString("text");target=r.data().optString("title");page=1}){Text("载入为编辑副本")}
                    DocumentExportButtons(r.data().optString("title"),r.data().optString("text"))
                })}
            }
        }
    }
}

@Composable
fun InterviewWorkspace(repo:StudentRepository,records:List<StudentRecord>,back:()->Unit) {
    var page by rememberSaveable{mutableIntStateOf(0)}
    var target by rememberSaveable{mutableStateOf("")}
    var material by rememberSaveable{mutableStateOf("")}
    var count by rememberSaveable{mutableIntStateOf(5)}
    var language by rememberSaveable{mutableIntStateOf(0)}
    var initialRun by rememberSaveable{mutableStateOf("")}
    var loaded by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){
        repo.dao.get("draft","interview-studio")?.data()?.let{d->target=d.optString("target");material=d.optString("text");count=d.optInt("count",5);language=d.optInt("language",0)}
        loaded=true
    }
    LaunchedEffect(target,material,count,language,loaded){if(loaded){delay(700);repo.save("draft",JSONObject().put("target",target).put("text",material).put("count",count).put("language",language),"interview-studio")}}
    Column(Modifier.fillMaxSize()){
        AgentHeader("面试训练营","逐题追问 · 暂停续练 · 复盘",back,true)
        StudentTabs(listOf("准备","开始练习","复盘记录"),page){page=it}
        when(page){
            0 -> StudentPage(""){
                Surface(color=Color(0xFFE6F4F0),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                        Text("像正式面试一样，练一轮",fontSize=20.sp,fontWeight=FontWeight.Bold)
                        Text("围绕你的材料提问，根据回答追问。每次只解决一个问题，结束后生成下一步练习。",fontSize=13.sp,color=IosMuted)
                    }
                }
                OutlinedTextField(target,{target=it},label={Text("目标学校专业与练习方向")},modifier=Modifier.fillMaxWidth())
                StudentTabs(listOf("中文练习","英文练习"),language){language=it}
                StudentTabs(listOf("3 题热身","5 题训练","10 题模拟"),listOf(3,5,10).indexOf(count)){count=listOf(3,5,10)[it]}
                Text("选择简历作为练习背景",fontWeight=FontWeight.SemiBold)
                records.filter{it.kind=="resume"}.take(6).forEach{r->StudentCard(r.data().optString("title"),r.data().optString("text").take(80),onClick={material=r.data().optString("text")})}
                OutlinedTextField(material,{material=it},label={Text("本人背景和项目经历，可直接输入")},modifier=Modifier.fillMaxWidth(),minLines=6)
                Button(onClick={page=1},colors=ButtonDefaults.buttonColors(containerColor=InterviewGreen)){Text("设置好了，开始练习")}
                Text("文字练习支持随时暂停；不需要上传录音。",fontSize=12.sp,color=IosMuted)
            }
            else -> AgentWorkbench(repo,"interview",material,"${if(language==0)"中文" else "英文"}；$target",initialRun,{initialRun=it},questionCount=count,historyOnly=page==2)
        }
    }
}

@Composable
fun GeneralAgentWorkspace(repo:StudentRepository,kind:String,title:String,initial:String="",initialFile:String="",back:()->Unit) {
    var text by rememberSaveable(kind){mutableStateOf(initial)}
    var target by rememberSaveable(kind){mutableStateOf("")}
    var run by rememberSaveable(kind){mutableStateOf("")}
    Column(Modifier.fillMaxSize()){
        AgentHeader(title,"保存任务进度，把结果带回工作台",back)
        AgentWorkbench(repo,kind,text,target,run,{run=it},initialFile=initialFile,extra={
            OutlinedTextField(target,{target=it},label={Text("你希望完成什么")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(text,{text=it},label={Text("本次材料与已知条件")},modifier=Modifier.fillMaxWidth(),minLines=6)
        })
    }
}

@Composable
fun AgentWorkbench(repo:StudentRepository,kind:String,text:String,target:String,initialRun:String,onRun:(String)->Unit,
                   mode:String="全面优化",questionCount:Int=5,historyOnly:Boolean=false,initialFile:String="",
                   onApplySuggestion:((String,String)->String)?=null,extra:@Composable ()->Unit={}) {
    var list by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var detail by remember{mutableStateOf<JSONObject?>(null)}
    var error by remember{mutableStateOf("")}
    var consent by rememberSaveable(text,target,mode){mutableStateOf(false)}
    var key by rememberSaveable(text,target,mode,questionCount){mutableStateOf(UUID.randomUUID().toString())}
    var selected by rememberSaveable{mutableStateOf(initialRun)}
    var fileIds by rememberSaveable{mutableStateOf(initialFile)}
    var files by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var answer by rememberSaveable(selected){mutableStateOf("")}
    var deleteConfirm by remember{mutableStateOf(false)}
    var notice by remember{mutableStateOf("")}
    var answerLoaded by remember(selected){mutableStateOf(false)}
    LaunchedEffect(selected){
        if(selected.isNotBlank())answer=repo.dao.get("draft","agent-answer-$selected")?.data()?.optString("text")?:""
        answerLoaded=true
    }
    LaunchedEffect(answer,selected,answerLoaded){if(selected.isNotBlank()&&answerLoaded){delay(600);repo.save("draft",JSONObject().put("text",answer),"agent-answer-$selected")}}
    suspend fun refresh(){
        list=repo.api("agents/runs?kind=$kind").getJSONArray("items").objects()
        if(selected.isNotBlank())detail=repo.api("agents/runs/$selected")
    }
    LaunchedEffect(selected,detail?.optString("status")){
        do{
            runCatching{refresh()}.onFailure{error=it.message?:"连接失败"}
            if(detail?.optString("status") !in listOf("queued","running"))break
            delay(4000)
        }while(true)
    }
    BackHandler(selected.isNotBlank()){selected="";detail=null;onRun("")}
    StudentPage(""){
        if(selected.isBlank()){
            if(!historyOnly){
                extra()
                if(kind in listOf("resume","interview"))StudentCard("本次材料预览",if(text.isBlank())"请先准备正文，至少 20 字。" else text.take(800)+(if(text.length>800)"\n共 ${text.length} 字，完整正文见编辑页" else ""))
                OnlineAction("选择私人资料作为补充") {files=repo.api("files").getJSONArray("items").objects();"已加载本人文件；只有勾选的文件会用于本次任务"}
                files.forEach{f->val ids=fileIds.split('|').toSet();Row(verticalAlignment=Alignment.CenterVertically){Checkbox(f.getString("id") in ids,{yes->fileIds=(if(yes)ids+f.getString("id") else ids-f.getString("id")).filter{it.isNotBlank()}.joinToString("|");consent=false;key=UUID.randomUUID().toString()});Text(f.optString("name"),fontSize=13.sp)}}
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(consent,{consent=it});Text("我已核对正文、目标和所选文件，同意用于本次云端 AI 任务",fontSize=12.sp,modifier=Modifier.weight(1f))}
                if(consent)OnlineAction(if(kind=="interview")"创建一轮智能面试" else "开始智能任务"){
                    val body=JSONObject().put("request_key",key).put("kind",kind).put("text",text).put("target",target).put("mode",mode).put("question_count",questionCount).put("consent",true)
                        .put("file_ids",JSONArray(fileIds.split('|').filter{it.isNotBlank()}))
                    val created=repo.api("agents/runs","POST",body);selected=created.getString("id");onRun(selected);refresh();"任务已保存，可离开页面后继续查看"
                }
            }
            IosSection("任务记录")
            OnlineAction("刷新任务记录"){refresh();"已刷新"}
            if(list.isEmpty())Text("还没有任务记录。需要邮箱登录后使用云端助手。",fontSize=13.sp,color=IosMuted)
            list.forEach{r->StudentCard(r.optString("title"),"${agentStatus(r.optString("status"))} · ${r.optJSONArray("stages")?.optString(r.optInt("stage"))?:""}",onClick={selected=r.getString("id");onRun(selected)})}
        }else{
            TextButton(onClick={selected="";detail=null;onRun("")}){Text("返回任务列表")}
            val run=detail
            if(run!=null){
                val status=run.optString("status");val revision=run.optInt("revision")
                Text(agentStatus(status),fontSize=19.sp,fontWeight=FontWeight.Bold,color=if(kind=="interview")InterviewGreen else IosBlue)
                if(status in listOf("queued","running"))LinearProgressIndicator(Modifier.fillMaxWidth())
                val steps=run.optJSONArray("steps")?.objects()?:emptyList()
                IosGroup{
                    steps.forEachIndexed{i,s->
                        Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            Icon(if(s.optString("status")=="completed")Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,null,tint=if(s.optString("status")=="completed")InterviewGreen else IosMuted,modifier=Modifier.size(20.dp))
                            Text(s.optString("label"),Modifier.weight(1f),fontSize=13.sp);Text(agentStatus(s.optString("status")),fontSize=12.sp,color=IosMuted)
                        }
                        if(i<steps.lastIndex)HorizontalDivider(color=Color(0xFFF0F0F3))
                    }
                }
                val output=run.optJSONObject("output")?:JSONObject()
                if(output.optString("summary").isNotBlank())StudentCard("当前结果",output.optString("summary"))
                if(status=="waiting"){
                    val prompt=if(kind=="interview")"第 ${output.optInt("question_number")} / ${output.optInt("question_count")} 题\n${output.optString("question")}" else output.optJSONArray("questions")?.strings()?.joinToString("\n")?:"请补充材料"
                    StudentCard(if(kind=="interview")"轮到你回答" else "先确认这些事实",prompt)
                    if(output.optString("feedback").isNotBlank())StudentCard("上一题反馈",output.optString("feedback"))
                    OutlinedTextField(answer,{answer=it},label={Text("你的回答，提交后用于继续本次 AI 任务")},modifier=Modifier.fillMaxWidth(),minLines=6)
                    OnlineAction("提交回答并继续"){
                        repo.api("agents/runs/$selected/continue","POST",JSONObject().put("revision",revision).put("answer",answer).put("consent",true));answer="";refresh();"回答已保存"
                    }
                    OnlineAction(if(kind=="interview")"跳过这一题" else "暂不补充，仅依据已有事实"){
                        repo.api("agents/runs/$selected/continue","POST",JSONObject().put("revision",revision).put("skip",true).put("consent",true));answer="";refresh();"已继续处理"
                    }
                    Text("离开页面会保留当前练习进度。",fontSize=12.sp,color=IosMuted)
                }
                if(status=="completed"){
                    for(field in listOf("analysis","audit"))if(output.optString(field).isNotBlank())Text(output.optString(field),fontSize=13.sp)
                    for(field in listOf("strengths","improvements","warnings","questions"))output.optJSONArray(field)?.strings()?.forEach{Text("• $it",fontSize=13.sp)}
                    output.optJSONArray("sections")?.objects()?.forEach{s->StudentCard(s.optString("title"),s.optString("body"))}
                    output.optJSONArray("suggestions")?.objects()?.forEach{s->StudentCard("逐段修改", "原文\n${s.optString("before")}\n\n建议\n${s.optString("after")}\n\n${s.optString("reason")}",actions={
                        if(onApplySuggestion!=null)TextButton(onClick={notice=onApplySuggestion(s.optString("before"),s.optString("after"))}){Text("采用到草稿")}
                    })}
                    output.optJSONArray("citations")?.objects()?.forEach{c->StudentCard("引用 · ${c.optString("name")} · 第 ${c.optInt("page")} 页或段",c.optString("quote"))}
                    output.optJSONArray("tasks")?.objects()?.forEachIndexed{i,t->
                        var title by remember(selected,i){mutableStateOf(t.optString("title"))}
                        var due by remember(selected,i){mutableStateOf(t.optString("due"))}
                        StudentCard("建议事项 ${i+1}",t.optString("note"),actions={
                            OutlinedTextField(title,{title=it},label={Text("确认任务内容")},modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(due,{due=it},label={Text("个人计划日期 YYYY-MM-DD，可留空")},modifier=Modifier.fillMaxWidth())
                            OnlineAction("确认加入我的待办"){
                                val body=JSONObject().put("revision",revision).put("item",i).put("title",title).put("due",due).put("note",t.optString("note"))
                                val result=repo.api("agents/runs/$selected/apply-task","POST",body)
                                repo.save("task",result.optJSONObject("data")?:JSONObject().put("title",title).put("due",due).put("note",t.optString("note")).put("agent_run",selected),result.getString("id"));refresh();"已加入本机与云端待办，重复点击不会新增"
                            }
                        })
                    }
                    val report=agentReport(run)
                    DocumentExportButtons(if(kind=="interview")"面试复盘" else "智能任务报告",report)
                }
                run.optJSONArray("turns")?.objects()?.forEach{t->StudentCard("练习 ${t.optInt("turn")+1}","${t.optString("question")}\n\n${t.optString("answer")}\n\n${t.optString("feedback")}")}
                if(status=="failed"){
                    Text(run.optString("error"),color=MaterialTheme.colorScheme.error)
                    OnlineAction("从失败步骤重试"){repo.api("agents/runs/$selected/retry","POST",JSONObject().put("revision",revision));refresh();"已保留完成步骤并重新排队"}
                }
                OnlineAction("刷新当前任务"){refresh();"已刷新"}
                if(status !in listOf("completed","cancelled"))OnlineAction("停止后续处理"){repo.api("agents/runs/$selected/cancel","POST",JSONObject().put("revision",revision));refresh();"后续步骤已停止"}
                TextButton(onClick={deleteConfirm=true}){Text("删除任务记录")}
            }
        }
        if(notice.isNotBlank())Text(notice,color=IosBlue,fontSize=13.sp)
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)
    }
    if(deleteConfirm)AlertDialog(onDismissRequest={deleteConfirm=false},title={Text("删除这项任务？")},text={Text("输入材料快照、结果和练习记录会从任务中心删除。已采用的个人文件和待办保留。")},confirmButton={OnlineAction("确认删除"){
        repo.api("agents/runs/$selected","DELETE");selected="";detail=null;onRun("");deleteConfirm=false;refresh();"任务已删除"
    }},dismissButton={TextButton(onClick={deleteConfirm=false}){Text("保留")}})
}
