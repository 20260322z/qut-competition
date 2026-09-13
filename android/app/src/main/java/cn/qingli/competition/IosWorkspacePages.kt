package cn.qingli.competition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun IosHome(records: List<StudentRecord>, notices: List<NoticeItem>, route: (String)->Unit, open: (NoticeItem)->Unit) {
    val profile=records.find{it.kind=="profile"}?.data() ?: JSONObject()
    val grades=records.filter{it.kind=="grade"}.map{Grade.from(it.data())}
    val stats=summarizeGrades(grades)
    val tasks=records.filter{it.kind=="task" && !it.data().optBoolean("done")}.sortedBy{it.data().optString("due","9999")}
    val due=notices.filter{it.state.favorite && (it.deadlineMillis?:0)>System.currentTimeMillis()}.sortedBy{it.deadlineMillis}
    StudentPage("今天",LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("M月d日 EEEE",Locale.CHINA))+" · 青理竞赛通") {
        IosGroup {
            IosRow("今天，向目标近一步",profile.optString("school","青岛理工大学")+" · "+profile.optString("goal").ifBlank{"设置我的方向"},Icons.Outlined.School,divider=false){route("profile")}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            IosMetric(stats.average?.let{number(it)}?:"—","加权成绩",Modifier.weight(1f))
            IosMetric(stats.gpa?.let{number(it)}?:"—","平均绩点",Modifier.weight(1f),IosInk)
            IosMetric(tasks.size.toString(),"待办事项",Modifier.weight(1f),IosInk)
        }
        IosGroup { IosRow("搜索工作台","赛事、通知、私人资料和个人记录",Icons.Outlined.Search,divider=false){route("search_all")} }
        IosGroup { IosRow("智能任务中心","简历、面试、学习与资料的处理进度",Icons.Outlined.AutoAwesome,divider=false){route("agents")} }
        IosSection("常用功能")
        IosToolGrid(listOf(
            IosTool("学习待办",Icons.Outlined.Checklist,"tasks"),
            IosTool("竞赛日程",Icons.Outlined.CalendarMonth,"calendar"),
            IosTool("目标院校",Icons.Outlined.AccountBalance,"targets"),
            IosTool("申请进度",Icons.Outlined.FactCheck,"applications"),
            IosTool("简历工作室",Icons.Outlined.Description,"resume"),
            IosTool("面试练习",Icons.Outlined.RecordVoiceOver,"interview"),
            IosTool("资料中心",Icons.Outlined.FolderOpen,"materials"),
            IosTool("邮箱提醒",Icons.Outlined.MailOutline,"email")
        ),open=route)
        IosSection("接下来要做","全部待办"){route("tasks")}
        IosGroup {
            if(tasks.isEmpty() && due.isEmpty())IosRow("添加今天的第一件事","学习任务与收藏竞赛会集中显示在这里",Icons.Outlined.AddTask,divider=false){route("tasks")}
            else {
                tasks.take(2).forEachIndexed { index,record ->
                    IosRow(record.data().optString("title"),record.data().optString("due").ifBlank{"尚未设置日期"},Icons.Outlined.RadioButtonUnchecked,divider=index<minOf(tasks.size,2)-1 || due.isNotEmpty()){route("tasks")}
                }
                due.take(1).forEach { n ->
                    IosRow(n.notice.title,"竞赛截止 · ${n.notice.deadline?.take(10)?:"自定日期"}",Icons.Outlined.Event,divider=false){open(n)}
                }
            }
        }
        Text("学业统计来自已确认的 ${grades.size} 门课程。",fontSize=11.sp,lineHeight=16.sp,color=IosMuted)
    }
}

@Composable
fun IosAdmission(records: List<StudentRecord>, route: (String)->Unit) {
    val count=records.count{it.kind=="target"}
    val applications=records.count{it.kind=="application"}
    StudentPage("升学准备","$count 所目标院校 · $applications 条申请记录") {
        IosGroup { IosRow("检查申请要求与材料缺口","选择已保存目标，生成可确认的行动清单",Icons.Outlined.AutoAwesome,divider=false){route("agent_admission")} }
        IosSection("规划与申请")
        IosGroup {
            IosRow("我的目标院校","学校、专业与申请要求对比",Icons.Outlined.AccountBalance){route("targets")}
            IosRow("申请进度与材料清单","批次、截止日期与材料版本",Icons.Outlined.FactCheck){route("applications")}
            IosRow("学习计划","每周任务与统一日程",Icons.Outlined.Checklist,divider=false){route("tasks")}
        }
        IosSection("简历、面试与材料")
        IosGroup {
            IosRow("简历工作室","档案引用、AI 修改、版本与导出",Icons.Outlined.Description){route("resume")}
            IosRow("面试练习","问答练习、回答记录与反馈",Icons.Outlined.RecordVoiceOver){route("interview")}
            IosRow("资料中心","公共资料 · 私人文件 · 打印准备",Icons.Outlined.FolderOpen,divider=false){route("files")}
        }
        Text("申请要求以官方发布为准，进度由本人确认。",fontSize=11.sp,lineHeight=16.sp,color=IosMuted)
    }
}

@Composable
fun IosProfile(repo: StudentRepository, records: List<StudentRecord>, route: (String)->Unit) {
    val account by repo.account.collectAsStateWithLifecycle()
    val profile=records.find{it.kind=="profile"}?.data() ?: JSONObject()
    val scope=rememberCoroutineScope()
    StudentPage("我的","个人信息、消息和设置集中管理") {
        IosGroup {
            IosRow(if(account.isBlank())"游客 · 本机工作台" else account,if(account.isBlank())"登录邮箱，开启同步与组队" else "账号与同步",Icons.Outlined.PersonOutline,divider=false){route("account")}
        }
        IosGroup {
            IosRow("个人信息与目标","学校、专业与升学方向",Icons.Outlined.Badge){route("profile")}
            IosRow("消息与处理进度","组队申请、评论与团队成果",Icons.Outlined.NotificationsNone){route("inbox")}
            IosRow("我的收藏","已缓存通知可离线阅读",Icons.Outlined.BookmarkBorder){route("favorites")}
            IosRow("邮箱提醒","收件邮箱、订阅与截止提醒",Icons.Outlined.MailOutline,divider=false){route("email")}
        }
        IosGroup {
            IosRow("资料中心","公共资料 · 私人文件 · 打印准备",Icons.Outlined.FolderOpen){route("materials")}
            IosRow("设置与连接","通知权限、服务地址与帮助",Icons.Outlined.Settings){route("settings")}
            Row(Modifier.padding(horizontal=13.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                IosIcon(Icons.Outlined.TouchApp,size=30)
                Text("操作成功时轻触反馈",Modifier.weight(1f),fontSize=13.sp)
                Switch(profile.optBoolean("haptics",true),{enabled->
                    scope.launch{repo.save("profile",profile.put("haptics",enabled),"me")}
                    repo.context.getSharedPreferences("student_feedback",0).edit().putBoolean("enabled",enabled).apply()
                })
            }
        }
        Text("青理竞赛通 · 个人学习工具，非学校官方应用。",fontSize=11.sp,lineHeight=16.sp,color=IosMuted)
    }
}

@Composable
fun MaterialHub(repo: StudentRepository, initial: Int, back: ()->Unit) {
    var selected by rememberSaveable(initial){mutableIntStateOf(initial)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回",tint=IosBlue)}
            Text("资料中心",fontSize=22.sp,fontWeight=FontWeight.Bold)
        }
        StudentTabs(listOf("公共资料","私人文件","打印准备"),selected){selected=it}
        key(selected) {
            when(selected){
                0 -> ResourceScreen(repo,true,null,embedded=true)
                1 -> ResourceScreen(repo,false,null,embedded=true)
                else -> PrintPreparationScreen(null,embedded=true)
            }
        }
    }
}
