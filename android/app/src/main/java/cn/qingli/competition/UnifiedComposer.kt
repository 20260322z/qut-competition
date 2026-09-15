package cn.qingli.competition

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.time.LocalDate

/** Shared post/task editor. Content first; optional attributes live in chips and a sheet. */
@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
fun UnifiedDraftEditor(fields:List<Pair<String,String>>,values:Map<String,String>,change:(Map<String,String>)->Unit) {
    val context=LocalContext.current
    val title=fields.firstOrNull{it.first=="title"}?:fields.first()
    val body=fields.firstOrNull{it.first in listOf("body","note","text","reason","roles") && it.first!=title.first}
    val isTask=title.second.contains("任务")||title.second.contains("事项")
    val multiline=title.first in listOf("body","text","note","reason")
    var active by remember{mutableStateOf<Pair<String,String>?>(null)}
    var search by remember{mutableStateOf("")}
    val colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent)
    TextField(values[title.first].orEmpty(),{change(values+(title.first to it))},placeholder={Text(if(title.second.contains("任务")||title.second.contains("事项"))"准备做什么？" else if(title.first=="title")"准备分享什么？" else title.second,fontSize=21.sp)},
        modifier=Modifier.fillMaxWidth(),textStyle=TextStyle(fontSize=if(multiline)16.sp else 22.sp,fontWeight=if(multiline)FontWeight.Normal else FontWeight.SemiBold,lineHeight=28.sp),
        minLines=if(multiline)4 else 1,maxLines=if(multiline)12 else 3,colors=colors)
    if(body!=null)TextField(values[body.first].orEmpty(),{change(values+(body.first to it))},placeholder={Text(if(isTask)"补充备注、准备材料或完成标准…" else if(body.first=="roles")"写下招募需求、技能要求和你想一起完成的事…" else "写点具体内容，让别人更好地理解…",fontSize=15.sp)},modifier=Modifier.fillMaxWidth(),minLines=if(isTask)2 else 4,maxLines=12,colors=colors,textStyle=TextStyle(fontSize=16.sp,lineHeight=25.sp))
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        fields.filter{it!=title && it!=body}.forEach{field->
            val key=field.first;val raw=values[key].orEmpty();val label=when(key){"schedule"->"时间安排";"capacity"->"队伍人数";"goal"->"参赛目标";"location"->"线上 / 校区";else->field.second.substringBefore(' ').substringBefore('：')}
            AssistChip(onClick={
                if(key in listOf("date","due")){
                    val day=runCatching{LocalDate.parse(raw)}.getOrDefault(LocalDate.now())
                    DatePickerDialog(context,{_,y,m,d->change(values+(key to LocalDate.of(y,m+1,d).toString()))},day.year,day.monthValue-1,day.dayOfMonth).show()
                }else{active=field;search=""}
            },label={Text(if(raw.isBlank())label else if(key=="capacity")"$raw 人" else raw.take(22),fontSize=12.sp,maxLines=1)},leadingIcon={Icon(if(key in listOf("date","due"))Icons.Outlined.CalendarToday else if(key=="contest")Icons.Outlined.EmojiEvents else Icons.Outlined.Label,null,Modifier.size(15.dp))})
        }
    }
    active?.let{field->ModalBottomSheet(onDismissRequest={active=null}){
        Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(field.second.substringBefore(' '),fontSize=20.sp,fontWeight=FontWeight.Bold)
            if(field.first=="contest"){
                val names=remember{JSONArray(context.assets.open("contest_sources.json").bufferedReader().use{it.readText()}).objects().map{it.optString("name")}}
                CompactSearch(search,{search=it},"搜索赛事，也可填写目录外赛事",{})
                if(search.isNotBlank())TextButton(onClick={change(values+(field.first to search));active=null}){Text("使用：$search")}
                names.filter{search.isBlank()||it.contains(search,true)}.forEach{name->TextButton(onClick={change(values+(field.first to name));active=null}){Text(name,fontSize=13.sp)}}
            }else{
                TextField(values[field.first].orEmpty(),{change(values+(field.first to it))},placeholder={Text(field.second)},modifier=Modifier.fillMaxWidth(),minLines=if(field.first in listOf("goal","schedule","materials"))3 else 1)
                if(field.first=="source")ChoiceChips(listOf("自定","竞赛","升学"),values[field.first].orEmpty()){change(values+(field.first to it))}
                if(field.first=="capacity")ChoiceChips(listOf("2","3","4","5","6"),values[field.first].orEmpty()){change(values+(field.first to it))}
                Button(onClick={active=null},modifier=Modifier.fillMaxWidth()){Text("完成")}
            }
            TextButton(onClick={change(values+(field.first to ""));active=null}){Text("清除这一项")}
        }
    }}
}
