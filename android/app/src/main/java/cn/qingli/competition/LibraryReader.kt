package cn.qingli.competition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun LibraryReader(repo:StudentRepository,file:JSONObject,public:Boolean,back:()->Unit){
    val id=file.getString("id");val name=file.optString("name")
    var data by remember{mutableStateOf<JSONObject?>(null)}
    var page by rememberSaveable(id){mutableIntStateOf(1)}
    var query by rememberSaveable(id){mutableStateOf("")}
    var error by remember{mutableStateOf("")}
    var agent by rememberSaveable{mutableStateOf(false)}
    var bitmap by remember{mutableStateOf<android.graphics.Bitmap?>(null)}
    var pageCount by remember{mutableIntStateOf(0)}
    var versions by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var previous by rememberSaveable{mutableStateOf("")}
    var note by rememberSaveable{mutableStateOf("")}
    var owned by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    LaunchedEffect(id){
        runCatching{data=repo.api("library/files/$id/text")}.onFailure{error=it.message?:"读取失败"}
        runCatching{val mark=repo.api("library/bookmarks").getJSONArray("items").objects().firstOrNull{it.optString("id")==id};if(mark!=null)page=mark.optInt("page",1)}
    }
    if(agent){
        BackHandler{agent=false}
        GeneralAgentWorkspace(repo,"resource","读懂这份资料","请依据所选文件回答问题，逐条给出可核对的原文位置。文件：$name",id){agent=false}
        return
    }
    StudentPage(name,"读取位置保留在个人账号；文件修改以独立版本保存",back){
        if(error.isNotBlank())Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        val pages=data?.optJSONArray("pages")?.objects()?:emptyList()
        val maximum=maxOf(pages.size,pageCount,1)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick={page=(page-1).coerceAtLeast(1);bitmap=null},enabled=page>1){Text("上一页")}
            Text("$page / $maximum",Modifier.padding(top=12.dp))
            OutlinedButton(onClick={page=(page+1).coerceAtMost(maximum);bitmap=null},enabled=page<maximum){Text("下一页")}
        }
        if(name.endsWith(".pdf",true))OnlineAction("显示本页原版预览"){
            val bytes=repo.download("${if(public)"resources" else "files"}/$id/download")
            val rendered=withContext(Dispatchers.IO){
                val temporary=java.io.File.createTempFile("reader-",".pdf",repo.context.cacheDir)
                try{
                    temporary.writeBytes(bytes)
                    android.os.ParcelFileDescriptor.open(temporary,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use{fd->
                        android.graphics.pdf.PdfRenderer(fd).use{reader->
                            val total=reader.pageCount
                            reader.openPage((page-1).coerceIn(0,total-1)).use{p->
                                val width=1000;val height=(width.toDouble()*p.height/p.width).toInt().coerceIn(1,4000)
                                val bmp=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE);p.render(bmp,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                total to bmp
                            }
                        }
                    }
                }finally{temporary.delete()}
            }
            pageCount=rendered.first;bitmap=rendered.second;"原版预览已加载"
        }
        bitmap?.let{Image(it.asImageBitmap(),"PDF 第 $page 页原版预览",Modifier.fillMaxWidth())}
        OutlinedTextField(query,{query=it},label={Text("在这份资料中查找")},modifier=Modifier.fillMaxWidth())
        if(query.isNotBlank())pages.filter{it.optString("text").contains(query,true)}.take(15).forEach{p->
            StudentCard("第 ${p.optInt("page")} ${if(data?.optString("locator")=="page")"页" else "段"}",p.optString("text").let{val at=it.indexOf(query,ignoreCase=true);it.substring((at-40).coerceAtLeast(0),(at+160).coerceAtMost(it.length))},onClick={page=p.optInt("page");query="";bitmap=null})
        }
        Text(if(data?.optString("locator")=="page")"提取文字 · 第 $page 页" else "提取文字 · 第 $page 段（非原版分页）",fontSize=12.sp,color=IosMuted)
        Text(pages.getOrNull(page-1)?.optString("text")?:"暂无可提取文字，可以使用原版 PDF 预览或下载文件。",fontSize=14.sp)
        OnlineAction("收藏并记住阅读位置"){
            repo.api("library/files/$id/bookmark","PUT",JSONObject().put("page",page).put("favorite",true));"阅读位置已保存"
        }
        Button(onClick={agent=true}){Text("让助手解读这份资料")}
        OnlineAction("查看文件版本"){versions=repo.api("library/files/$id/versions").getJSONArray("items").objects();"已找到 ${versions.size} 个关联版本"}
        versions.forEach{v->StudentCard(v.optString("name"),v.optString("note"))}
        if(!public){
            OnlineAction("将此文件登记为新版本"){owned=repo.api("files").getJSONArray("items").objects().filter{it.optString("id")!=id};"请选择旧版本，原文件会保留"}
            owned.forEach{f->Row{RadioButton(previous==f.optString("id"),{previous=f.optString("id")});Text(f.optString("name"))}}
            if(previous.isNotBlank()){
                OutlinedTextField(note,{note=it},label={Text("本次修改说明")},modifier=Modifier.fillMaxWidth())
                OnlineAction("确认关联版本"){repo.api("library/files/$id/version","POST",JSONObject().put("previous",previous).put("note",note));previous="";owned=emptyList();"版本已关联，旧文件保留"}
            }
        }
    }
}
