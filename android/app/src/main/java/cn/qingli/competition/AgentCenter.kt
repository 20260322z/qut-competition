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
        IosSection("正在处理与历史任务")
        OnlineAction("刷新全部任务"){refresh();error="";"已刷新"}
        if(error.isNotBlank())Text(error,fontSize=12.sp,color=IosMuted)
        runs.forEach{r->StudentCard(r.optString("title"),agentStatus(r.optString("status"))+" · "+r.optJSONObject("output")?.optString("summary").orEmpty().take(160),onClick={
            val kind=r.optString("kind");navigate(if(kind=="grades")"agent_grades/${r.optString("id")}" else if(kind in listOf("resume","interview"))kind else "agent_$kind")
        })}
    }
}
