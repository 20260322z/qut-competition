package cn.qingli.competition

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
fun AgentCenter(repo:StudentRepository,records:List<StudentRecord>,navigate:(String)->Unit,back:()->Unit){
    var request by rememberSaveable{mutableStateOf("")}
    var runs by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var error by remember{mutableStateOf("")}
    suspend fun refresh(){runs=repo.api("agents/runs").getJSONArray("items").objects()}
    LaunchedEffect(Unit){runCatching{refresh()}.onFailure{error=it.message?:"请登录后查看任务"}}
    StudentPage("智能任务中心","查看真实处理进度，继续补充或采用结果",back){
        OutlinedTextField(request,{request=it},label={Text("记录你想完成的事情")},modifier=Modifier.fillMaxWidth(),minLines=2)
        if(request.isNotBlank())OnlineAction("保存需求到待办"){
            repo.save("task",JSONObject().put("title",request.take(160)).put("note",request).put("source","本人记录").put("done",false));"需求已保存，可选择下面的工具开始处理"
        }
        IosGroup{
            IosRow("简历工作室","事实核对、逐段修改与版本导出",Icons.Outlined.Description){navigate("resume")}
            IosRow("面试训练营","材料驱动的追问、续练与复盘",Icons.Outlined.RecordVoiceOver){navigate("interview")}
            IosRow("学业助手","根据已确认课程安排学习任务",Icons.Outlined.School){navigate("agent_study")}
            IosRow("升学助手","读取目标与要求，整理申请材料",Icons.Outlined.AccountBalance){navigate("agent_admission")}
            IosRow("日程助手","检查已有事项并提出调整建议",Icons.Outlined.CalendarMonth){navigate("agent_schedule")}
            IosRow("资料助手","带出处阅读、提问与复习清单",Icons.Outlined.FolderOpen){navigate("agent_resource")}
            IosRow("档案助手","整理本人贡献与证明材料",Icons.Outlined.Badge,divider=false){navigate("agent_portfolio")}
        }
        IosSection("正在处理与历史任务")
        OnlineAction("刷新全部任务"){refresh();error="";"已刷新"}
        if(error.isNotBlank())Text(error,fontSize=12.sp,color=IosMuted)
        runs.forEach{r->StudentCard(r.optString("title"),agentStatus(r.optString("status"))+" · "+r.optJSONObject("output")?.optString("summary").orEmpty().take(160),onClick={
            val kind=r.optString("kind");navigate(if(kind in listOf("resume","interview"))kind else "agent_$kind")
        })}
    }
}
