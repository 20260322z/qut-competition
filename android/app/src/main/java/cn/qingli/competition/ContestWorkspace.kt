package cn.qingli.competition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

fun sourceStatus(v:String)=when(v){"verified"->"已核对来源";"archived"->"历史来源";else->"待核验来源"}

@Composable
fun ContestDetail(repo:StudentRepository,id:String,initial:JSONObject,back:()->Unit){
    var data by remember(id){mutableStateOf(initial)}
    var page by rememberSaveable(id){mutableIntStateOf(0)}
    var agent by rememberSaveable(id){mutableStateOf("")}
    var event by remember{mutableStateOf<JSONObject?>(null)}
    var error by remember{mutableStateOf("")}
    suspend fun refresh(){data=repo.api("contests/$id");error=""}
    LaunchedEffect(id){runCatching{refresh()}.onFailure{error=it.message?:"暂时无法更新"}}
    val name=data.optString("current_name").ifBlank{data.optString("name")}
    if(agent.isNotBlank()){
        BackHandler{agent=""}
        GeneralAgentWorkspace(repo,agent,when(agent){"team"->"招募整理";"experience"->"经验整理";else->"赛事准备助手"},
            "赛事：$name\n目录版本：2023，不能用于推定综测加分。\n已收录公开资料：\n"+(data.optJSONArray("events")?.objects()?.take(3)?.joinToString("\n\n"){"${it.optString("title")}\n来源：${it.optString("url")}\n${it.optString("body").take(4500)}"}?:"暂无可读取的本届规则，请补充官方原文。")){agent=""}
        return
    }
    Column(Modifier.fillMaxSize()){
        AgentHeader("赛事详情",name,back)
        Text("相关通知请在首页使用赛事筛选查看",modifier=Modifier.padding(horizontal=16.dp),fontSize=12.sp,color=IosMuted)
        when(page){
            0 -> StudentPage(""){
                Text(name,fontSize=21.sp,fontWeight=FontWeight.Bold)
                Text(sourceStatus(data.optString("verification"))+" · 2023 目录",fontSize=12.sp,color=IosMuted)
                Text(data.optString("remarks"),fontSize=13.sp)
                val following=data.optJSONObject("follow")
                OnlineAction(if(following==null)"关注赛事与后续变化" else "取消关注"){
                    repo.api("contests/$id/follow","PUT",JSONObject().put("enabled",following==null));refresh();"关注设置已更新"
                }
                if(following!=null){
                    OnlineAction(if(following.optInt("deadline")==1)"关闭本赛事截止邮件" else "开启本赛事截止邮件"){
                        repo.api("contests/$id/follow","PUT",JSONObject().put("enabled",true).put("news",following.optInt("news")==1).put("deadline",following.optInt("deadline")!=1));refresh();"已更新"
                    }
                    Text("邮件需先在“我的—邮箱提醒”绑定并启用；首次关注不补发历史通知。",fontSize=12.sp,color=IosMuted)
                }
                IosSection("信息来源")
                StudentLink(if(data.optString("verification")=="verified")"赛事官方入口" else "查看候选来源",data.optString("official_url"))
                if(data.optString("registration_url").isNotBlank())StudentLink("报名平台（核对当届开放状态）",data.optString("registration_url"))
                StudentLink("来源核验依据",data.optString("evidence"))
                StudentCard("当届参赛条件","人数、资格、费用和赛道请核对通知原文。没有明确原文时均待确认。")
                data.optJSONObject("check")?.let{check->
                    Text("最近成功检查："+if(check.optDouble("success",0.0)>0)java.time.Instant.ofEpochSecond(check.optDouble("success").toLong()).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime().toString() else "尚未成功采集",fontSize=12.sp,color=IosMuted)
                    if(check.optString("error").isNotBlank()&&check.optString("error")!="null")Text(check.optString("error"),fontSize=12.sp,color=IosMuted)
                }
                Button(onClick={agent="contest"}){Text("解读规则并制定准备清单")}
                OnlineAction("刷新本页"){refresh();"已更新"}
                if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
            }

        }
    }
    event?.let{e->AlertDialog(onDismissRequest={event=null},title={Text(e.optString("title"))},text={
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            item{Text(e.optString("scope"),color=IosBlue)}
            val dates=e.optJSONArray("stages")?.objects()?:emptyList()
            if(dates.isEmpty())item{Text("截止时间待确认，未自动推定报名日期。")}
            dates.forEach{s->item{StudentCard(s.optString("stage"),"${s.optString("date")} ${s.optString("time").ifBlank{"具体时刻未注明"}}\n${s.optString("evidence")}")}}
            item{Text(e.optString("body"),fontSize=13.sp)}
            item{StudentLink("查看原文",e.optString("url"))}
        }
    },confirmButton={TextButton(onClick={event=null}){Text("关闭")}})}
}
