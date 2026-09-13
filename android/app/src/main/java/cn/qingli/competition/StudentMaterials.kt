package cn.qingli.competition

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream

fun displayName(context: android.content.Context, uri: Uri): String = context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{
    if(it.moveToFirst())it.getString(0) else null
} ?: "未命名文件"

fun readBounded(context: android.content.Context, uri: Uri, max: Int = 10*1024*1024): ByteArray {
    return context.contentResolver.openInputStream(uri)?.use{input->
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192);var count:Int
        while(input.read(buffer).also{count=it}!=-1){require(out.size()+count<=max){"文件超过 ${max/1024/1024} MB"};out.write(buffer,0,count)}
        out.toByteArray()
    }?:error("无法读取文件，请重新选择")
}

@Composable
fun ResourceScreen(repo: StudentRepository, public: Boolean, back:(()->Unit)?, embedded:Boolean=false) {
    var reading by remember{mutableStateOf<JSONObject?>(null)}
    if(reading!=null){androidx.activity.compose.BackHandler{reading=null};LibraryReader(repo,reading!!,public){reading=null};return}
    var query by rememberSaveable{mutableStateOf("")};var list by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var uri by remember{mutableStateOf<Uri?>(null)};var name by remember{mutableStateOf("")};var category by rememberSaveable{mutableStateOf("")};var relation by rememberSaveable{mutableStateOf("")}
    var share by remember{mutableStateOf(false)};var consent by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf<JSONObject?>(null)};var error by remember{mutableStateOf("")};var deleting by remember{mutableStateOf<JSONObject?>(null)}
    var shareExisting by remember{mutableStateOf<JSONObject?>(null)};var reporting by remember{mutableStateOf<JSONObject?>(null)}
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u->if(u!=null){uri=u;name=displayName(context,u)}}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){target->
        val file=selected
        if(target!=null&&file!=null)scope.launch{
            error=runCatching{
                val bytes=repo.download("${if(public)"resources" else "files"}/${file.getString("id")}/download")
                withContext(Dispatchers.IO){context.contentResolver.openOutputStream(target)?.use{it.write(bytes)}?:error("无法写入")}
                "文件已完整下载到所选位置，可离线打开"
            }.getOrElse{it.message?:"下载失败，请重试"}
        }
    }
    suspend fun refresh(){list=repo.api("${if(public)"resources" else "files"}?q=${java.net.URLEncoder.encode(query,"UTF-8")}").getJSONArray("items").objects()}
    LaunchedEffect(public){runCatching{refresh()}.onFailure{error=it.message?:"读取失败"}}
    StudentPage(if(embedded)"" else if(public)"公共资料" else "私人文件","单文件 10 MB，个人空间 200 MB；下载完成后才可离线使用",back) {
        OutlinedTextField(query,{query=it},label={Text("课程 / 赛事 / 年份 / 关键词")},modifier=Modifier.fillMaxWidth())
        OnlineAction("搜索 / 刷新"){refresh();"已加载 ${list.size} 个文件"}
        if(error.isNotBlank())Text(error)
        list.forEach{f->StudentCard(f.optString("name"),"${f.optLong("size")/1024} KB · ${f.optJSONObject("data")?.optString("category")?:""}\n${f.optJSONObject("data")?.optString("context")?:""}",actions={
            TextButton(onClick={reading=f}){Text("预览、阅读与智能解读")}
            OutlinedButton(onClick={selected=f;saver.launch(f.getString("name"))}){Text("下载到手机")}
            if(!public){
                val currentlyPublic=f.optInt("public")==1
                Text(if(currentlyPublic)"当前公开" else "当前仅自己可见")
                if(currentlyPublic)OnlineAction("取消公开"){repo.api("files/${f.getString("id")}/sharing","PUT",JSONObject().put("public",false));refresh();"已停止新的公开下载；他人已下载的副本无法收回"}
                else TextButton(onClick={shareExisting=f}){Text("公开分享")}
                TextButton(onClick={deleting=f}){Text("删除文件")}
            } else TextButton(onClick={reporting=f}){Text("举报资料问题")}
        })}
        if(list.isEmpty())Text("当前没有相关资料。未上传的文件不会出现在公共空间。")
        Text("上传资料",style=MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick={picker.launch(arrayOf("application/pdf","text/plain","application/zip","image/*","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))}){Text(if(uri==null)"选择文件" else "重新选择：$name")}
        OutlinedTextField(category,{category=it},label={Text("分类 / 课程")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(relation,{relation=it},label={Text("学校、专业、赛事或年份")},modifier=Modifier.fillMaxWidth())
        Row{Checkbox(share,{share=it;consent=false});Text("主动公开分享此文件")}
        if(share)Row{Checkbox(consent,{consent=it});Text("已预览原文件，确认有权分享，且无成绩单、学号、联系方式等私人内容")}
        if(uri!=null)OnlineAction(if(share)"上传并公开分享" else "上传到私人空间"){
            require(!share||consent){"公开分享前请确认文件内容与授权"}
            val bytes=withContext(Dispatchers.IO){readBounded(context,uri!!)}
            val result=repo.api("files","POST",JSONObject().put("name",name).put("content",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
                .put("category",category).put("context",relation).put("public",share).put("rights_confirmed",consent))
            refresh();uri=null; if(result.optBoolean("duplicate"))"检测到相同文件，保留原记录与分享状态" else "上传成功"
        }
    }
    deleting?.let{f->AlertDialog(onDismissRequest={deleting=null},title={Text("删除文件？")},text={Text(f.optString("name")+"\n云端文件删除后不能继续下载，已下载副本不会删除。")},confirmButton={TextButton(onClick={scope.launch{runCatching{repo.api("files/${f.getString("id")}","DELETE");refresh()}.onFailure{error=it.message?:"删除失败"};deleting=null}}){Text("确认删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("保留")}})}
    shareExisting?.let{f->AlertDialog(onDismissRequest={shareExisting=null},title={Text("确认公开分享")},text={Text("${f.optString("name")}\n确认已经查看原文件、有权分享，且不含成绩单、学号或联系方式等私人信息。公开后其他人可以下载。")},confirmButton={TextButton(onClick={scope.launch{runCatching{repo.api("files/${f.getString("id")}/sharing","PUT",JSONObject().put("public",true).put("rights_confirmed",true));refresh()}.onFailure{error=it.message?:"分享失败"};shareExisting=null}}){Text("确认并公开")}},dismissButton={TextButton(onClick={shareExisting=null}){Text("保持私有")}})}
    reporting?.let{f->AlertDialog(onDismissRequest={reporting=null},title={Text("举报资料问题")},text={Column{EditFields(listOf("reason" to "问题说明"),JSONObject(),"提交举报"){repo.api("reports","POST",it.put("target","file:${f.getString("id")}"));reporting=null}}},confirmButton={},dismissButton={TextButton(onClick={reporting=null}){Text("取消")}})}
}

@Composable
fun PrintPreparationScreen(back:(()->Unit)?, embedded:Boolean=false) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var files by remember{mutableStateOf<List<Uri>>(emptyList())};var copies by rememberSaveable{mutableStateOf("1")};var pages by rememberSaveable{mutableStateOf("全部")}
    var duplex by rememberSaveable{mutableStateOf(true)};var color by rememberSaveable{mutableStateOf(false)};var binding by rememberSaveable{mutableStateOf("不装订")};var message by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){files=it.distinct()}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){target->if(target!=null)scope.launch{
        busy=true
        message=runCatching{withContext(Dispatchers.IO){
            val count=copies.toIntOrNull();require(count!=null&&count in 1..100){"份数应为 1—100"}
            require(pages=="全部"||pages.matches(Regex("[0-9,，\\- ]+"))){"页码请填写全部，或 1-3,5 这样的范围"}
            require(files.isNotEmpty()&&files.size<=20){"请选择 1—20 个文件"}
            val names=files.map{displayName(context,it)}
            val content=ByteArrayOutputStream()
            ZipOutputStream(content).use{zip->
                val manifest="打印准备清单（不是商家订单）\n份数：$copies\n页码：$pages（各文件均按此范围，请对照原件核对）\n单双面：${if(duplex)"双面" else "单面"}\n颜色：${if(color)"彩色" else "黑白"}\n装订：$binding\n文件按以下顺序排列；原件没有修改\n"+names.mapIndexed{i,n->"${i+1}. $n"}.joinToString("\n")
                zip.putNextEntry(ZipEntry("打印清单.txt"));zip.write(manifest.toByteArray());zip.closeEntry()
                var total=0
                files.forEachIndexed{i,u->val bytes=readBounded(context,u);total+=bytes.size;require(total<=50*1024*1024){"总文件大小超过 50 MB"}
                    val safe=names[i].replace(Regex("[\\\\/:*?\"<>|]"),"_")
                    zip.putNextEntry(ZipEntry("${i+1}_$safe"));zip.write(bytes);zip.closeEntry()
                }
            }
            context.contentResolver.openOutputStream(target)?.use{it.write(content.toByteArray())}?:error("无法保存")
        };"已导出文件与打印清单。请在交给商家前打开核对页码、签名与盖章。"}.getOrElse{it.message?:"导出失败"}
        busy=false
    }}
    StudentPage(if(embedded)"" else "打印准备","尚未接入商家，不收款、不生成订单或物流状态",back) {
        OutlinedButton(onClick={choose.launch(arrayOf("application/pdf","image/*","application/vnd.openxmlformats-officedocument.wordprocessingml.document"))}){Text("选择 1—20 个文件")}
        files.forEachIndexed{i,u->StudentCard("${i+1}. ${displayName(context,u)}","请在原文件中核对页数、签名、盖章与文件版本",actions={Row{if(i>0)TextButton(onClick={files=files.toMutableList().also{java.util.Collections.swap(it,i,i-1)}}){Text("上移")};TextButton(onClick={files=files.filter{it!=u}}){Text("移除")}}})}
        OutlinedTextField(copies,{copies=it},label={Text("份数")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(pages,{pages=it},label={Text("页码范围，例如 全部 或 1-3,5")},modifier=Modifier.fillMaxWidth())
        Row{Checkbox(duplex,{duplex=it});Text("双面打印")};Row{Checkbox(color,{color=it});Text("彩色打印")}
        OutlinedTextField(binding,{binding=it},label={Text("装订要求")},modifier=Modifier.fillMaxWidth())
        Button(enabled=!busy&&files.isNotEmpty(),onClick={export.launch("打印准备.zip")}){Text(if(busy)"正在打包…" else "导出文件包与清单")}
        if(message.isNotBlank())Text(message)
    }
}

@Composable
fun ResumeScreen(repo: StudentRepository, records: List<StudentRecord>, back:()->Unit) {
    var text by rememberSaveable{mutableStateOf(records.firstOrNull{it.kind=="resume"}?.data()?.optString("text")?:"")}
    var target by rememberSaveable{mutableStateOf("")};var selected by remember{mutableStateOf<Set<String>>(emptySet())};var consent by remember{mutableStateOf(false)}
    var jobs by remember{mutableStateOf<List<JSONObject>>(emptyList())};var requestKey by rememberSaveable{mutableStateOf(UUID.randomUUID().toString())}
    StudentPage("简历工作室","原文、建议与保存版本分开；是否采用由你决定",back) {
        Text("选择要引用的个人经历")
        records.filter{it.kind=="portfolio"}.forEach{r->Row{Checkbox(r.id in selected,{selected=if(it)selected+r.id else selected-r.id});Text(r.data().optString("title"))}}
        OutlinedButton(enabled=selected.isNotEmpty(),onClick={
            text+=(if(text.isBlank())"" else "\n\n")+records.filter{it.id in selected}.joinToString("\n\n"){r->val j=r.data();"${j.optString("title")}\n职责：${j.optString("role")}\n贡献：${j.optString("note")}\n成果：${j.optString("result")}"}
            consent=false;requestKey=UUID.randomUUID().toString()
        }){Text("复制所选经历到初稿")}
        OutlinedTextField(target,{target=it;consent=false;requestKey=UUID.randomUUID().toString()},label={Text("使用场景与目标，例如 推免面试 / 项目申请")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(text,{text=it;consent=false;requestKey=UUID.randomUUID().toString()},label={Text("简历正文，可先删去姓名、电话与邮箱")},modifier=Modifier.fillMaxWidth(),minLines=10)
        OnlineAction("保存为本机新版本"){require(text.isNotBlank()){ "请填写正文" };repo.save("resume",JSONObject().put("title",target.ifBlank{"简历"}).put("text",text));"版本已保存，成长档案未改动"}
        Row{Checkbox(consent,{consent=it});Text("已预览上方文字，确认将正文与目标发送给 DeepSeek 进行修改建议")}
        if(consent)OnlineAction("提交 AI 修改任务"){
            val result=repo.api("ai/jobs","POST",JSONObject().put("request_key",requestKey).put("kind","resume").put("text",text).put("target",target).put("consent",true))
            "任务已保存（${result.optString("status")}），离开页面后可在下方任务记录继续查看"
        }
        OnlineAction("刷新 AI 任务记录"){jobs=repo.api("ai/jobs").getJSONArray("items").objects().filter{it.optString("kind")=="resume"};"已刷新"}
        jobs.forEach{j->StudentCard("AI 任务 · ${j.optString("status")}",j.optString("error").replace("null",""),actions={
            val output=j.optJSONObject("output")
            if(output!=null){
                Text(output.optString("summary"))
                (output.optJSONArray("suggestions")?:JSONArray()).objects().forEach{s->
                    Text("原文：${s.optString("before")}\n建议：${s.optString("after")}\n原因：${s.optString("reason")}")
                    TextButton(onClick={val before=s.optString("before");if(before.isNotBlank()&&text.contains(before)){text=text.replaceFirst(before,s.optString("after"));consent=false;requestKey=UUID.randomUUID().toString()}}){Text("采用此段（仍可编辑）")}
                }
                Text("需要补充：${output.optJSONArray("questions")?:JSONArray()}")
            }
            if(j.optString("status")=="failed")OnlineAction("重试此任务"){repo.api("ai/jobs/${j.getString("id")}/retry","POST");"已重新排队，不新增一次请求记录"}
            OnlineAction("删除此任务记录"){repo.api("ai/jobs/${j.getString("id")}","DELETE");jobs=jobs.filter{it.getString("id")!=j.getString("id")};"记录已删除"}
        })}
        Text("本机版本",style=MaterialTheme.typography.titleMedium)
        records.filter{it.kind=="resume"}.take(20).forEach{r->StudentCard(r.data().optString("title"),"${java.time.Instant.ofEpochMilli(r.updated).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime()}\n${r.data().optString("text").take(180)}",actions={TextButton(onClick={text=r.data().optString("text");target=r.data().optString("title");consent=false;requestKey=UUID.randomUUID().toString()}){Text("载入为编辑副本")}})}
        DocumentExportButtons("简历",text)
    }
}

@Composable
fun InterviewScreen(repo: StudentRepository, records: List<StudentRecord>, back:()->Unit) {
    val questions=listOf("请用一分钟介绍自己，并说明希望研究的方向。","为什么选择这个专业或研究方向？","介绍一个你亲自参与的项目，你具体负责什么？","项目中遇到最大的困难是什么，你如何解决？","请解释一门核心课程中的重要概念，并举例说明。","你最有代表性的竞赛经历是什么，结果和个人贡献分别是什么？","如果实验结果不符合预期，你会怎样排查？","谈谈你的不足，以及已经采取的改进措施。","未来一年你计划如何安排学习和研究？","还有哪些你希望向老师了解的问题？")
    var count by rememberSaveable{mutableIntStateOf(5)};var index by rememberSaveable{mutableIntStateOf(0)};var answer by rememberSaveable{mutableStateOf("")}
    var running by rememberSaveable{mutableStateOf(false)};var session by rememberSaveable{mutableStateOf(UUID.randomUUID().toString())};var consent by remember{mutableStateOf(false)}
    StudentPage("面试练习","文本练习 · 记录具体回答，不计算录取概率",back) {
        StudentCard("回答先讲清楚三件事","结论是什么、有什么真实例子、你本人做了什么。遇到不会的问题，可以说明已知条件与思考过程，不编造经历。")
        if(!running){StudentTabs(listOf("5 个问题","10 个问题"),if(count==5)0 else 1){count=if(it==0)5 else 10};Button(onClick={running=true;index=0;answer="";session=UUID.randomUUID().toString()}){Text("开始一轮练习")}}
        else {
            Text("第 ${index+1} / $count 题",style=MaterialTheme.typography.titleMedium);Text(questions[index])
            OutlinedTextField(answer,{answer=it;consent=false},label={Text("输入你的真实回答")},modifier=Modifier.fillMaxWidth(),minLines=7)
            OnlineAction("保存回答并继续"){
                require(answer.isNotBlank()){ "请先填写回答，或选择跳过" }
                repo.save("interview",JSONObject().put("title",questions[index]).put("question",questions[index]).put("answer",answer).put("session",session),"$session-$index")
                if(index+1==count)running=false else index++;answer="";consent=false;"已保存"
            }
            TextButton(onClick={if(index+1==count)running=false else index++;answer="";consent=false}){Text("跳过此题")}
            TextButton(onClick={running=false}){Text("结束本轮（已保存回答保留）")}
            Row{Checkbox(consent,{consent=it});Text("将当前问题和回答发送给 DeepSeek，获取表达与逻辑反馈")}
            if(consent)OnlineAction("提交本题反馈任务"){
                repo.api("ai/jobs","POST",JSONObject().put("request_key","$session-$index-${answer.hashCode()}").put("kind","interview").put("text",questions[index]+"\n"+answer).put("target","升学面试练习").put("consent",true));"已提交，可在下方刷新反馈"
            }
        }
        var results by remember{mutableStateOf<List<JSONObject>>(emptyList())}
        OnlineAction("刷新面试反馈"){results=repo.api("ai/jobs").getJSONArray("items").objects().filter{it.optString("kind")=="interview"};"已刷新"}
        results.forEach{j->StudentCard("反馈 · ${j.optString("status")}",j.optJSONObject("output")?.let{o->o.optString("summary")+"\n"+(o.optJSONArray("suggestions")?:JSONArray()).objects().joinToString("\n"){it.optString("reason")+"\n"+it.optString("after")}}?:j.optString("error").replace("null",""))}
        Text("已保存的回答",style=MaterialTheme.typography.titleMedium)
        records.filter{it.kind=="interview"}.take(30).forEach{r->StudentCard(r.data().optString("question"),r.data().optString("answer"),actions={OnlineAction("删除回答"){repo.dao.delete("interview",r.id);"已删除"}})}
    }
}

/** Standard OOXML and Android PDF exports work offline and preserve the edited text. */
fun makeDocx(title:String,text:String):ByteArray {
    fun xml(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
    val out=ByteArrayOutputStream()
    ZipOutputStream(out).use{z->
        fun entry(name:String,value:String){z.putNextEntry(ZipEntry(name));z.write(value.toByteArray());z.closeEntry()}
        entry("[Content_Types].xml","""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
        entry("_rels/.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
        val paragraphs=(title+"\n"+text).lines().joinToString(""){"<w:p><w:r><w:t xml:space=\"preserve\">${xml(it)}</w:t></w:r></w:p>"}
        entry("word/document.xml","""<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr></w:body></w:document>""")
    };return out.toByteArray()
}
fun makePdf(title:String,text:String):ByteArray {
    val document=android.graphics.pdf.PdfDocument();val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{textSize=12f;color=android.graphics.Color.BLACK}
    var pageNumber=1;var page=document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595,842,pageNumber).create());var y=50f
    for(paragraph in (title+"\n\n"+text).lines()) {
        var remaining=paragraph
        do{
            if(y>790){document.finishPage(page);pageNumber++;page=document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595,842,pageNumber).create());y=50f}
            val count=paint.breakText(remaining,true,495f,null).coerceAtLeast(if(remaining.isNotEmpty())1 else 0)
            page.canvas.drawText(remaining.take(count),50f,y,paint);y+=19f;remaining=remaining.drop(count)
        }while(remaining.isNotEmpty())
    }
    document.finishPage(page);val out=ByteArrayOutputStream();document.writeTo(out);document.close();return out.toByteArray()
}
@Composable
fun DocumentExportButtons(title:String,text:String) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();var message by remember{mutableStateOf("")};var format by remember{mutableStateOf("pdf")};var busy by remember{mutableStateOf(false)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)scope.launch{
        busy=true;message=runCatching{withContext(Dispatchers.IO){val bytes=if(format=="pdf")makePdf(title,text) else makeDocx(title,text);context.contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:error("无法保存")};"已导出，请打开文件核对分页、占位文字和个人信息"}.getOrElse{it.message?:"导出失败"};busy=false
    }}
    Text("导出前请检查事实与空白项；导出结果不会自动提交给学校。")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){for(f in listOf("pdf","docx"))OutlinedButton(enabled=text.isNotBlank()&&!busy,onClick={format=f;export.launch("$title.$f")}){Text("导出 ${f.uppercase()}")}}
    if(message.isNotBlank())Text(message)
}
