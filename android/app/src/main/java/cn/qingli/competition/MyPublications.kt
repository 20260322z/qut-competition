package cn.qingli.competition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
fun MyPublications(repo:StudentRepository,back:()->Unit) {
    var teams by remember{mutableStateOf<List<JSONObject>>(emptyList())};var posts by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var selected by remember{mutableStateOf<Pair<Boolean,JSONObject>?>(null)};var kind by remember{mutableStateOf("全部")};var error by remember{mutableStateOf("")}
    var page by remember{mutableStateOf(1)};var more by remember{mutableStateOf(false)}
    suspend fun load(){teams=repo.api("teams/mine").getJSONArray("items").objects().filter{it.optString("owner")==repo.owner()};posts=repo.api("posts?mine=true").getJSONArray("items").objects();page=1;more=posts.size==30}
    LaunchedEffect(Unit){runCatching{load()}.onFailure{error=it.message?:"请先登录"}}
    selected?.let{(team,item)->BackHandler{selected=null};if(team)TeamDetailScreen(repo,item.getString("id")){selected=null}else PostDetailScreen(repo,item){selected=null};return}
    StudentPage("我的发布","招募帖子和经验帖子，统一在这里管理",back){
        ChoiceChips(listOf("全部","找队友","经验交流"),kind){kind=it}
        OnlineAction("刷新我的发布"){load();error="";"已刷新"}
        if(error.isNotBlank())Text(error,fontSize=12.sp,color=IosMuted)
        val rows=(if(kind!="经验交流")teams.map{true to it} else emptyList())+(if(kind!="找队友")posts.map{false to it} else emptyList())
        if(rows.isEmpty())ReferenceEmpty("还没有发布内容","点底部 ＋ 写一篇招募或经验，发布后会自动出现在这里。")
        rows.sortedByDescending{it.second.optDouble("updated")}.forEach{(team,item)->StudentCard(item.optString("title"),(if(team)"找队友" else "经验交流")+" · "+item.optString("contest"),{selected=team to item})}
        if(more&&kind!="找队友")OnlineAction("加载更早的发布"){val next=repo.api("posts?mine=true&page=${page+1}").getJSONArray("items").objects();posts=(posts+next).distinctBy{it.optString("id")};page++;more=next.size==30;"已加载"}
    }
}
