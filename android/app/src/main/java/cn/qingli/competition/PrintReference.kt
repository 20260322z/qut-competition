package cn.qingli.competition

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun validPrintRange(value:String):Boolean = value=="全部" || value.replace('，',',').split(',').all { segment ->
    val match=Regex("\\s*([1-9]\\d{0,4})(?:\\s*-\\s*([1-9]\\d{0,4}))?\\s*").matchEntire(segment)
    match!=null && (match.groupValues[2].isBlank() || match.groupValues[2].toInt()>=match.groupValues[1].toInt())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintReference(back:(()->Unit)?,embedded:Boolean=false) {
    val context=LocalContext.current;val repo=remember{context.students()};val scope=rememberCoroutineScope()
    val records by repo.records.collectAsStateWithLifecycle(emptyList())
    var files by remember{mutableStateOf<List<Uri>>(emptyList())};var copies by rememberSaveable{mutableStateOf("1")};var pages by rememberSaveable{mutableStateOf("全部")}
    var duplex by rememberSaveable{mutableStateOf(true)};var color by rememberSaveable{mutableStateOf(false)};var binding by rememberSaveable{mutableStateOf("不装订")}
    var message by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};var settings by remember{mutableStateOf(false)};var history by remember{mutableStateOf(false)};var help by remember{mutableStateOf(false)}
    val formats=arrayOf("application/pdf","image/*","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.presentationml.presentation")
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){selected->
        val combined=(files+selected).distinct()
        if(combined.size>20)message="一次最多选择 20 个文件" else files=combined
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){target->if(target!=null)scope.launch{
        busy=true
        val chosen=files.toList();val count=copies.toIntOrNull();val range=pages;val doubleSide=duplex;val colored=color;val bind=binding
        message=runCatching {
            require(count!=null&&count in 1..100){"份数应为 1—100"};require(validPrintRange(range)){"页码请填全部或 1-3,5；不支持 0 页及倒序范围"};require(chosen.size in 1..20){"请选择 1—20 个文件"}
            val names=chosen.map{displayName(context,it)}
            withContext(Dispatchers.IO){
                val output=ByteArrayOutputStream()
                ZipOutputStream(output).use{zip->
                    val manifest="打印准备清单\n份数：$count\n页码：$range（每份文件采用同一范围，请核对原件）\n颜色：${if(colored)"彩色" else "黑白"}\n单双面：${if(doubleSide)"双面" else "单面"}\n装订：$bind\n"+names.mapIndexed{i,n->"${i+1}. $n"}.joinToString("\n")
                    zip.putNextEntry(ZipEntry("打印清单.txt"));zip.write(manifest.toByteArray());zip.closeEntry()
                    var total=0
                    chosen.forEachIndexed{i,u->val bytes=readBounded(context,u);total+=bytes.size;require(total<=50*1024*1024){"文件总大小超过 50 MB，请分批导出"}
                        zip.putNextEntry(ZipEntry("${i+1}_"+names[i].replace(Regex("[\\\\/:*?\"<>|]"),"_")));zip.write(bytes);zip.closeEntry()
                    }
                }
                context.contentResolver.openOutputStream(target)?.use{it.write(output.toByteArray())}?:error("不能写入所选位置")
            }
            repo.save("print_bundle",JSONObject().put("title",names.first()+if(names.size>1)" 等 ${names.size} 个文件" else "").put("files",JSONArray(names)).put("copies",count).put("pages",range).put("binding",bind).put("color",colored).put("duplex",doubleSide))
            "已导出原文件与打印清单，可以交给打印店。"
        }.getOrElse{it.message?:"导出失败，原文件未改动"}
        busy=false
    }}
    StudentPage(if(embedded)"" else "资料打印",back=back) {
        ReferenceBanner("整理好，一次打印","选文件 · 调整要求 · 导出打印包",Icons.Outlined.Print) {
            Button(onClick={choose.launch(formats)},modifier=Modifier.fillMaxWidth()){Text(if(files.isEmpty())"选择文件，开始打印准备" else "继续添加文件")}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            TextButton(onClick={choose.launch(formats)}){Text("手机文件",fontSize=12.sp)}
            TextButton(onClick={help=true}){Text("微信 / QQ 文件",fontSize=12.sp)}
            TextButton(onClick={choose.launch(arrayOf("*/*"))}){Text("网盘文件",fontSize=12.sp)}
        }
        IosGroup {
            IosRow("打印要求","${if(color)"彩色" else "黑白"} · ${if(duplex)"双面" else "单面"} · $copies 份 · $binding",Icons.Outlined.Tune){settings=true}
            IosRow("已导出记录","${records.count{it.kind=="print_bundle"}} 份打印包",Icons.Outlined.Inventory2,divider=false){history=true}
        }
        Text("待打印文件 · ${files.size}/20",fontSize=17.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
        if(files.isEmpty())ReferenceEmpty("把要打印的资料放在一起","支持 PDF、Word、PPT、图片。每份文件不超过 10 MB，总计不超过 50 MB。",Icons.Outlined.Print)
        files.forEachIndexed{i,u->StudentCard(displayName(context,u),"${i+1} · 页码 $pages",actions={Row{
            if(i>0)TextButton(onClick={files=files.toMutableList().also{java.util.Collections.swap(it,i,i-1)}}){Text("上移")}
            TextButton(onClick={files=files.filter{it!=u}}){Text("移除")}
        }})}
        Button(enabled=!busy&&files.isNotEmpty(),onClick={export.launch("打印准备.zip")},modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在整理文件…" else "导出文件与打印清单")}
        Text("商家服务尚未接入，当前可整理并导出文件包。价格、支付和配送将在接入商家后开放。",fontSize=11.sp,color=IosMuted)
        if(message.isNotBlank())Text(message,fontSize=12.sp,color=IosBlue)
    }
    if(settings)ModalBottomSheet(onDismissRequest={settings=false}) {Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("打印要求",fontSize=21.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
        ChoiceChips(listOf("黑白","彩色"),if(color)"彩色" else "黑白"){color=it=="彩色"}
        ChoiceChips(listOf("单面","双面"),if(duplex)"双面" else "单面"){duplex=it=="双面"}
        ChoiceChips(listOf("不装订","左侧装订","骑马订","胶装"),binding){binding=it}
        UnifiedDraftEditor(listOf("copies" to "打印份数","pages" to "页码范围"),mapOf("copies" to copies,"pages" to pages)){copies=it["copies"].orEmpty();pages=it["pages"].orEmpty()}
        Button(onClick={if(copies.toIntOrNull() in 1..100&&validPrintRange(pages))settings=false else message="请核对份数和页码范围"},modifier=Modifier.fillMaxWidth()){Text("完成设置")}
        if(message.isNotBlank())Text(message,fontSize=12.sp,color=IosBlue)
        Text("页码范围：全部 或 1-3,5。Word/PPT 页数随排版变化，请在原文件中核对。",fontSize=11.sp,color=IosMuted)
    }}
    if(history)ModalBottomSheet(onDismissRequest={history=false}){StudentPage("已导出记录","记录代表本机文件包已生成，不是商家订单"){
        val bundles=records.filter{it.kind=="print_bundle"}
        if(bundles.isEmpty())ReferenceEmpty("还没有导出记录","选择文件并完成导出后，这里会保存清单。")
        bundles.forEach{r->StudentCard(r.data().optString("title"),"${r.data().optInt("copies")} 份 · ${r.data().optString("pages")} · ${r.data().optString("binding")}")}
    }}
    if(help)AlertDialog(onDismissRequest={help=false},title={Text("使用聊天中的文件")},text={Text("先在微信或 QQ 中打开文件，保存到手机或用系统“其他应用打开”。然后回到这里选择手机文件。网盘文件可从系统文件选择器中选择已安装的网盘服务，或先下载到手机。")},confirmButton={TextButton(onClick={help=false;choose.launch(formats)}){Text("选择已保存的文件")}})
}
