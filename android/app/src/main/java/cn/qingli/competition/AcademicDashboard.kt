package cn.qingli.competition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademicDashboard(repo:StudentRepository,records:List<StudentRecord>,back:()->Unit,login:()->Unit,initialRun:String="") {
    val settings by repo.context.repository().settings.collectAsStateWithLifecycle(Settings())
    val grades=records.filter{it.kind=="grade"}.map{Grade.from(it.data())}
    val terms=grades.map{it.semester}.distinct().sortedByDescending(::semesterOrder)
    var selected by rememberSaveable{mutableStateOf("")}
    LaunchedEffect(terms){if(selected !in terms)selected=terms.firstOrNull().orEmpty()}
    var tool by rememberSaveable{mutableStateOf(false)}
    if(tool){BackHandler{tool=false};Column {CompactBack("文件导入与试算"){tool=false};GradesScreen(repo,records)};return}
    val current=grades.filter{it.semester==selected};val overall=summarizeGrades(grades);val term=summarizeGrades(current)
    var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    var course by remember{mutableStateOf<Grade?>(null)};var chosenAi by remember{mutableStateOf<List<Grade>?>(null)}
    var active by rememberSaveable(initialRun){mutableStateOf(initialRun)};var run by remember{mutableStateOf<JSONObject?>(null)}
    var report by remember{mutableStateOf<JSONObject?>(null)};val scope=rememberCoroutineScope()
    LaunchedEffect(Unit){if(active.isBlank())runCatching{repo.api("agents/runs?kind=grades").optJSONArray("items")?.objects()?.firstOrNull()}.getOrNull()?.let{active=it.optString("id")}}
    LaunchedEffect(active){
        if(active.isNotBlank())while(true){
            val destination=repo.dao
            val fetched=runCatching{repo.api("agents/runs/$active")}
            if(fetched.isFailure){message=fetched.exceptionOrNull()?.message?:"任务暂不可用";break}
            if(destination!==repo.dao){message="账号已切换，请重新打开分析";break}
            val next=fetched.getOrThrow()
            run=next
            if(next.optString("status") !in listOf("running","queued")){
                next.optJSONObject("output")?.takeIf{next.optString("status")=="completed"}?.let{report=it;destination.put(StudentRecord("grade_report",active,it.toString()))}
                break
            }
            delay(2500)
        }
    }
    StudentPage("成绩分析","教务登录一次，各学期成绩集中查看",back) {
        ReferenceBanner("学业总览","累计 GPA 按课程学分加权",Icons.Outlined.School) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                IosMetric(number(overall.gpa),"累计 GPA",Modifier.weight(1f))
                IosMetric(number(overall.average),"加权平均分",Modifier.weight(1f))
                IosMetric(grades.size.toString(),"课程数",Modifier.weight(1f))
            }
            val connected=settings.zhcpToken.isNotBlank()&&settings.zhcpRole=="student"
            Button(enabled=!busy,onClick={if(!connected)login() else scope.launch {
                busy=true;message="正在读取全部学期，请稍候…"
                val destination=repo.dao
                runCatching {
                    val incoming=repo.context.repository().transcriptFromSession()
                    require(destination===repo.dao){"账号已切换，请重新同步"}
                    destination.replaceGrades(incoming.map{g->StudentRecord("grade",UUID.nameUUIDFromBytes((g.semester+"\u0000"+g.course).toByteArray()).toString(),g.json().toString())})
                    selected=incoming.maxBy{semesterOrder(it.semester)}.semester;"已同步 ${incoming.size} 门课程、${incoming.map{it.semester}.distinct().size} 个学期，GPA 已计算。"
                }.onSuccess{message=it}.onFailure{message=it.message?:"同步失败，保留原成绩"};busy=false
            }},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.Sync,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(busy)"正在同步成绩…" else if(connected)"一键同步成绩并计算 GPA" else "登录综测教务系统")}
            Text(if(connected)"使用当前教务登录读取本人成绩；替换本机成绩前自动备份，可撤销。" else "登录后返回这里，无需再次填写学号和密码。",fontSize=11.sp,color=IosMuted)
        }
        if(message.isNotBlank())Text(message,fontSize=12.sp,color=IosBlue)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("学期成绩",fontSize=18.sp,fontWeight=FontWeight.Bold)
            TextButton(onClick={tool=true}){Text("导入 / 试算",fontSize=12.sp)}
        }
        if(terms.isEmpty())ReferenceEmpty("还没有成绩单","先登录综测教务，然后一键同步。也可以从成绩文件导入。",Icons.Outlined.Assessment)
        else {
            ChoiceChips(terms.map(::semesterLabel),semesterLabel(selected)){label->selected=terms.first{semesterLabel(it)==label}}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                IosMetric(number(term.gpa),"本学期 GPA",Modifier.weight(1f))
                IosMetric(number(term.average),"加权成绩",Modifier.weight(1f))
                IosMetric(number(term.gpaCredits),"绩点计入学分",Modifier.weight(1f))
            }
            Text("绩点采用教务原表值；缺失绩点不估算。补考、重修、免修暂排除，认定以学校规定为准。",fontSize=11.sp,color=IosMuted)
            val eligible=current.filter{it.status=="正常"&&it.credits>0}.sumOf{it.credits}
            if(term.gpaCredits<eligible)Text("部分课程缺少绩点：当前覆盖 ${number(term.gpaCredits)} / ${number(eligible)} 学分。",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
            Button(onClick={chosenAi=current},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.AutoAwesome,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("DeepSeek 分析本学期每门课")}
            current.sortedByDescending{it.credits}.forEach{g->
                Surface(onClick={course=g},shape=RoundedCornerShape(15.dp),color=androidx.compose.ui.graphics.Color.White) {
                    Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {Text(g.course,fontWeight=FontWeight.SemiBold,fontSize=15.sp);Text("${number(g.credits)} 学分 · GPA ${number(g.gpa)} · ${g.status}",fontSize=11.sp,color=IosMuted)}
                        Text(g.score?.let{number(it)}?:g.note.removePrefix("教务原始成绩：").ifBlank{"待确认"},fontSize=19.sp,fontWeight=FontWeight.Bold,color=if((g.score?:100.0)<60)MaterialTheme.colorScheme.error else IosBlue,modifier=Modifier.widthIn(max=85.dp))
                    }
                }
            }
            Text("历学期 GPA",fontSize=17.sp,fontWeight=FontWeight.Bold)
            terms.reversed().forEach{t->val s=summarizeGrades(grades.filter{it.semester==t});Row(Modifier.fillMaxWidth().padding(vertical=4.dp)){Text(semesterLabel(t),Modifier.weight(1f),fontSize=12.sp);Text(number(s.gpa),fontWeight=FontWeight.SemiBold,color=IosBlue)}}
        }
        run?.let{r->
            StudentCard("单科分析 · ${agentStatus(r.optString("status"))}",r.optString("error").takeUnless{it=="null"}.orEmpty(),actions={
                if(r.optString("status")=="failed")OnlineAction("从失败步骤重试"){repo.api("agents/runs/${r.getString("id")}/retry","POST",JSONObject().put("revision",r.getInt("revision")));val key=active;active="";delay(50);active=key;"正在重试"}
                if(r.optString("status")=="completed")TextButton(onClick={report=r.optJSONObject("output")}){Text("查看逐科建议")}
            })
        }
        records.filter{it.kind=="grade_report"}.take(3).forEach{r->TextButton(onClick={report=r.data()}){Text("查看已保存分析 · ${r.data().optJSONArray("courses")?.length()?:0} 门课程")}}
        if(records.any{it.kind=="draft"&&it.id=="grade-import-backup"})OnlineAction("撤销上一次成绩同步"){repo.dao.undoGradeImport();"已恢复之前的成绩"}
    }
    course?.let{g->ModalBottomSheet(onDismissRequest={course=null}){StudentPage(g.course,semesterLabel(g.semester)){
        Text("成绩 ${g.score?.let{number(it)}?:"待确认"} · ${number(g.credits)} 学分 · GPA ${number(g.gpa)}")
        Text(g.note,fontSize=12.sp,color=IosMuted)
        Text("成绩只反映这次考核结果。结合试卷、作业和错题，才能判断具体需要补什么。",fontSize=13.sp)
        Button(onClick={chosenAi=listOf(g);course=null},modifier=Modifier.fillMaxWidth()){Text("DeepSeek 分析这门课")}
    }}}
    chosenAi?.let{rows->AlertDialog(onDismissRequest={chosenAi=null},title={Text("分析 ${rows.size} 门课程")},text={Text("将课程名称、学期、成绩、学分和绩点发送给 DeepSeek，生成逐科建议。不会发送学号、教务密码或姓名。需要登录 App 邮箱账号。")},confirmButton={TextButton(onClick={scope.launch {
        val data=JSONObject().put("grades",JSONArray(rows.map{it.json().apply{remove("note")}}))
        runCatching{repo.api("agents/runs","POST",JSONObject().put("kind","grades").put("title",if(rows.size==1)rows[0].course+" · 单科分析" else semesterLabel(selected)+" · 学期分析").put("text",data.toString()).put("request_key",UUID.randomUUID().toString()).put("consent",true))}.onSuccess{active=it.getString("id");run=it;message="分析任务已提交，可以离开页面，结果在消息的 AI任务中保留。"}.onFailure{message=it.message?:"提交失败"}
        chosenAi=null
    }}){Text("确认分析")}},dismissButton={TextButton(onClick={chosenAi=null}){Text("取消")}})}
    report?.let{data->ModalBottomSheet(onDismissRequest={report=null}){StudentPage("逐科分析报告","DeepSeek 建议 · 实际成绩以教务原表为准"){
        Text(data.optString("summary"),fontSize=13.sp)
        (data.optJSONArray("courses")?:JSONArray()).objects().forEach{g->StudentCard(g.optString("course"),g.optString("assessment"),actions={
            val actions=g.optJSONArray("actions")?:JSONArray();for(i in 0 until actions.length())Text("${i+1}. ${actions.getString(i)}",fontSize=13.sp)
            Text("建议自查：${g.optString("check")}",fontSize=12.sp,color=IosMuted)
        })}
    }}}
}
