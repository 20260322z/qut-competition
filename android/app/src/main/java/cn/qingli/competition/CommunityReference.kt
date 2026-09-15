package cn.qingli.competition

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityReference(repo:StudentRepository,isTeam:Boolean,list:List<JSONObject>,query:String,onQuery:(String)->Unit,contest:String,onContest:(String)->Unit,
                       mine:Boolean,onMine:(Boolean)->Unit,message:String,refresh:()->Unit,open:(JSONObject)->Unit) {
    var filters by remember{mutableStateOf(false)};var onlyRecruiting by remember{mutableStateOf(false)}
    val names=remember{JSONArray(repo.context.assets.open("contest_sources.json").bufferedReader().use{it.readText()}).objects().map{it.optString("name")}}
    var contestSearch by remember{mutableStateOf("")}
    StudentPage("") {
        CompactSearch(query,onQuery,if(isTeam)"搜索赛事、技能或队伍" else "搜索比赛经验、备赛方法",refresh)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)){ChoiceChips(if(isTeam)listOf("全部招募","招募中","我的队伍") else listOf("全部经验","我的发布"),if(mine)if(isTeam)"我的队伍" else "我的发布" else if(onlyRecruiting)"招募中" else if(isTeam)"全部招募" else "全部经验") {
                onMine(it in listOf("我的队伍","我的发布"));onlyRecruiting=it=="招募中"
            }}
            IconButton(onClick={filters=true}){Icon(Icons.Outlined.Tune,"选择赛事",tint=IosBlue)}
        }
        if(contest.isNotBlank())InputChip(true,{filters=true},label={Text(contest,fontSize=11.sp,maxLines=1)},trailingIcon={IconButton(onClick={onContest("")},modifier=Modifier.size(24.dp)){Icon(Icons.Outlined.Close,"清除赛事",Modifier.size(15.dp))}})
        if(message.isNotBlank())Text(message,fontSize=12.sp,color=IosMuted)
        val visible=list.filter{(!onlyRecruiting || it.optString("status") in listOf("open","recruiting")) && (query.isBlank()||it.toString().contains(query,true)) && (contest.isBlank()||it.optString("contest")==contest)}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(if(isTeam)"一起把比赛做好" else "把经验留给后来的人",fontWeight=FontWeight.Bold,fontSize=17.sp)
            Text("${visible.size} 条",fontSize=12.sp,color=IosMuted)
        }
        if(visible.isEmpty())ReferenceEmpty(if(isTeam)"还没有合适的招募" else "这里等你分享经验",if(isTeam)"试试其他赛事，或点底部 ＋ 发起组队。" else "备赛方法、分工经历、踩坑心得，都可以从底部 ＋ 分享。",if(isTeam)Icons.Outlined.Groups else Icons.Outlined.Forum)
        else visible.forEach{t->
            Surface(onClick={open(t)},shape=RoundedCornerShape(18.dp),color=Color.White) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        IosIcon(if(isTeam)Icons.Outlined.Groups else Icons.Outlined.Forum,IosBlue)
                        Column(Modifier.weight(1f).padding(start=10.dp)) {Text(if(t.optString("owner")==repo.owner())"我的发布" else if(isTeam)"同学的组队招募" else "同学的经验分享",fontWeight=FontWeight.Medium,fontSize=13.sp);Text(t.optString("contest"),fontSize=11.sp,color=IosMuted,maxLines=1)}
                        Text(if(!isTeam)"经验" else if(t.optString("status") in listOf("open","recruiting"))"招募中" else "已结束",fontSize=11.sp,color=IosBlue)
                    }
                    val data=t.optJSONObject("data")?:JSONObject()
                    Text(t.optString("title"),fontWeight=FontWeight.Bold,fontSize=17.sp,maxLines=2)
                    Text(if(isTeam)data.optString("roles").ifBlank{"点击查看分工和能力要求"} else t.optString("body"),fontSize=14.sp,lineHeight=22.sp,color=IosMuted,maxLines=3)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(if(isTeam)"${t.optInt("members")}/${t.optInt("capacity")} 位成员 · ${data.optString("location").ifBlank{"地点待沟通"}}" else t.optString("year").let{if(it.isBlank())"备赛经验" else "$it 年参赛经验"},fontSize=11.sp,color=IosMuted)
                        Text("查看帖子 →",fontSize=12.sp,color=IosBlue,fontWeight=FontWeight.Medium)
                    }
                }
            }
        }
    }
    if(filters)ModalBottomSheet(onDismissRequest={filters=false}){StudentPage("按赛事筛选"){
        CompactSearch(contestSearch,{contestSearch=it},"搜索 84 项赛事",{})
        TextButton(onClick={onContest("");filters=false}){Text("全部赛事")}
        names.filter{contestSearch.isBlank()||it.contains(contestSearch,true)}.forEach{name->TextButton(onClick={onContest(name);filters=false},modifier=Modifier.fillMaxWidth()){Text(name,Modifier.fillMaxWidth(),fontSize=13.sp)}}
    }}
}
