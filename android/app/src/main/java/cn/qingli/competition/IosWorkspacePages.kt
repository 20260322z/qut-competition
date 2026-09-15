package cn.qingli.competition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MaterialHub(repo: StudentRepository, initial: Int, back: ()->Unit) {
    var selected by rememberSaveable(initial){mutableIntStateOf(initial)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回",tint=IosBlue)}
            Text("资料中心",fontSize=22.sp,fontWeight=FontWeight.Bold)
        }
        StudentTabs(listOf("共享空间","我的网盘","资料打印"),selected){selected=it}
        key(selected) {
            when(selected){
                0 -> ResourceScreen(repo,true,null,embedded=true)
                1 -> ResourceScreen(repo,false,null,embedded=true)
                else -> PrintReference(null,embedded=true)
            }
        }
    }
}
