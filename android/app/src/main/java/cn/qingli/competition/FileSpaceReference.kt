package cn.qingli.competition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

fun fileType(name:String)=name.substringAfterLast('.',"文件").uppercase().take(6)
fun fileColor(name:String)=when(fileType(name)){"PDF"->Color(0xFFEF6575);"DOCX","DOC"->IosBlue;"XLSX","XLS"->Color(0xFF15A582);else->Color(0xFF9378DC)}

@Composable
fun FileSpaceReference(public:Boolean,files:List<JSONObject>,query:String,onQuery:(String)->Unit,refresh:()->Unit,open:(JSONObject)->Unit,actions:@Composable (JSONObject)->Unit) {
    var category by rememberSaveable{mutableStateOf("全部")};var grid by rememberSaveable{mutableStateOf(public)};var order by rememberSaveable{mutableStateOf("最近更新")}
    var menu by remember{mutableStateOf<JSONObject?>(null)}
    fun categoryOf(file:JSONObject)=file.optJSONObject("data")?.optString("category").orEmpty().ifBlank{"学习资料"}
    val categories=listOf("全部")+files.map(::categoryOf).distinct().sorted()
    val visible=files.filter{category=="全部"||categoryOf(it)==category}.let{if(order=="文件名称")it.sortedBy{f->f.optString("name")} else it.sortedByDescending{f->f.optDouble("updated")}}
    val collections=public&&grid&&category=="全部"&&query.isBlank()
    ReferenceBanner(if(public)"一起分享，一起进步" else "我的资料，随时可用",if(public)"课程笔记 · 竞赛资料 · 备考经验" else "默认仅自己可见，公开分享由你决定",if(public)Icons.Outlined.FolderShared else Icons.Outlined.CloudQueue) {
        Text("已加载 ${files.size} 个文件 · ${number(files.sumOf{it.optLong("size")}/1024.0/1024.0)} MB",fontSize=12.sp,color=IosBlue)
    }
    CompactSearch(query,onQuery,"搜索课程、赛事、文件名",refresh)
    Row(verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.weight(1f)){ChoiceChips(categories,category){category=it}}
        IconButton(onClick={grid=!grid}){Icon(if(grid)Icons.Outlined.ViewList else Icons.Outlined.GridView,"切换列表或网格",tint=IosBlue)}
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text(if(collections)"共享空间 · 按分类整理" else if(public)"共享资料" else "全部文件",fontSize=17.sp,fontWeight=FontWeight.Bold)
        TextButton(onClick={order=if(order=="最近更新")"文件名称" else "最近更新"}){Text(order+" ↕",fontSize=12.sp)}
    }
    if(visible.isEmpty())ReferenceEmpty(if(public)"还没有匹配的共享资料" else "开始整理你的学习资料",if(public)"换个关键词，或通过底部 ＋ 分享你的文件。" else "点击底部 ＋ → 分享资料，选择文件并保存到私人空间。")
    if(collections)visible.groupBy(::categoryOf).entries.toList().chunked(2).forEach{pair->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
        pair.forEach{(name,items)->Surface(onClick={category=name},modifier=Modifier.weight(1f),shape=RoundedCornerShape(18.dp),color=Color.White){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Icon(Icons.Outlined.FolderShared,null,tint=IosBlue,modifier=Modifier.size(32.dp))
            Text(name,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=2)
            Text("${items.size} 个已加载文件",fontSize=11.sp,color=IosMuted)
            HorizontalDivider(color=IosMuted.copy(alpha=.1f))
            items.take(3).forEach{f->Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Description,null,tint=fileColor(f.optString("name")),modifier=Modifier.size(13.dp));Text(f.optString("name"),Modifier.padding(start=4.dp),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}}
            Text("进入空间 →",fontSize=12.sp,color=IosBlue)
        }}}
        if(pair.size==1)Spacer(Modifier.weight(1f))
    }} else if(grid)visible.chunked(2).forEach{pair->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
        pair.forEach{f->Surface(Modifier.weight(1f).iosClick{open(f)},shape=RoundedCornerShape(18.dp),color=Color.White){Column{
            Column(Modifier.fillMaxWidth().background(fileColor(f.optString("name")).copy(alpha=.09f)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Icon(Icons.Outlined.Description,null,tint=fileColor(f.optString("name")),modifier=Modifier.size(30.dp));Text(fileType(f.optString("name")),fontSize=10.sp,color=fileColor(f.optString("name")))}
                Text(f.optString("name"),fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,minLines=2,maxLines=3,overflow=TextOverflow.Ellipsis)
            }
            Column(Modifier.padding(11.dp)){
                Text(f.optJSONObject("data")?.optString("category").orEmpty().ifBlank{"学习资料"},fontSize=11.sp,color=IosBlue,maxLines=1)
                Row(verticalAlignment=Alignment.CenterVertically){Text("${f.optLong("size")/1024} KB",Modifier.weight(1f),fontSize=11.sp,color=IosMuted);IconButton(onClick={menu=f},modifier=Modifier.size(32.dp)){Icon(Icons.Outlined.MoreHoriz,"文件操作",Modifier.size(18.dp))}}
            }
        }}}
        if(pair.size==1)Spacer(Modifier.weight(1f))
    }} else visible.forEach{f->Surface(onClick={open(f)},shape=RoundedCornerShape(15.dp),color=Color.White){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
        IosIcon(Icons.Outlined.Description,fileColor(f.optString("name")));Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text(f.optString("name"),fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis);Text("${f.optLong("size")/1024} KB · ${f.optJSONObject("data")?.optString("category").orEmpty()}",fontSize=11.sp,color=IosMuted)}
        IconButton(onClick={menu=f}){Icon(Icons.Outlined.MoreHoriz,"文件操作")}
    }}}
    menu?.let{f->AlertDialog(onDismissRequest={menu=null},title={Text(f.optString("name"),fontSize=16.sp)},text={Column{actions(f)}},confirmButton={TextButton(onClick={menu=null}){Text("完成")}})}
}
