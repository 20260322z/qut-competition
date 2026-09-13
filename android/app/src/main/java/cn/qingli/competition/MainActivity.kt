package cn.qingli.competition

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale

private val Blue = IosBlue
private val Ink = IosInk
private val Muted = IosMuted
private val Background = IosBackground
private val Teal = Color(0xFF149F86)
private val Orange = Color(0xFFE98939)
private val CHINA = ZoneId.of("Asia/Shanghai")

class MainActivity : ComponentActivity() {
    private var incomingId = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incomingId.value = intent.getStringExtra("notice_id")
        setContent {
            QingliTheme { QutApp(incomingId.value) { incomingId.value = null } }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingId.value = intent.getStringExtra("notice_id")
    }
}

private fun dateLabel(millis: Long, pattern: String = "MM月dd日 HH:mm"): String =
    Instant.ofEpochMilli(millis).atZone(CHINA).format(DateTimeFormatter.ofPattern(pattern))

private fun syncLabel(value: String): String = runCatching {
    dateLabel(Instant.parse(value).toEpochMilli(), "MM-dd HH:mm")
}.getOrDefault("等待首次同步")

private fun daysUntil(millis: Long): Long = ChronoUnit.DAYS.between(
    LocalDate.now(CHINA), Instant.ofEpochMilli(millis).atZone(CHINA).toLocalDate())

private fun categoryColor(category: String) = when (category) {
    "外语" -> Color(0xFF8A5DD3)
    "创业" -> Teal
    "设计" -> Color(0xFFD97457)
    "科技" -> Blue
    else -> Color(0xFF657A9D)
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "外语" -> Icons.Outlined.Language
    "创业" -> Icons.Outlined.RocketLaunch
    "设计" -> Icons.Outlined.Palette
    "科技" -> Icons.Outlined.Science
    else -> Icons.Outlined.EmojiEvents
}

private fun openLink(context: android.content.Context, url: String) {
    runCatching {
        val uri = Uri.parse(url)
        require(uri.scheme == "http" || uri.scheme == "https")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }.onFailure { android.widget.Toast.makeText(context, "无法打开链接，请确认已安装浏览器", android.widget.Toast.LENGTH_SHORT).show() }
}

private fun chooseDate(context: android.content.Context, initial: Long?, selected: (Long) -> Unit) {
    val calendar = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
    calendar.timeInMillis = initial ?: (System.currentTimeMillis() + 2 * 86400000L)
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, minute ->
            calendar.set(y, m, d, h, minute, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            selected(calendar.timeInMillis)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = System.currentTimeMillis()
    }.show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QutApp(incomingId: String?, consumed: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { context.repository() }
    val items by repo.items.collectAsStateWithLifecycle(emptyList())
    val settings by repo.settings.collectAsStateWithLifecycle(Settings())
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    val appKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    var source by rememberSaveable { mutableStateOf("全部") }
    var upcoming by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var permissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionAction?.invoke()
        permissionAction = null
        if (!granted) scope.launch { snackbar.showSnackbar("系统通知未开启，仍可在日程中查看提醒") }
    }
    fun withPermission(action: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Reminders.permitted(context)) {
            permissionAction = action
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }
    fun sync() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            syncError = null
            try {
                val result = repo.sync()
                Reminders.announceNews(context, result)
            } catch (e: Exception) {
                syncError = "连接暂时不可用，正在显示已缓存内容"
                if (items.isEmpty()) syncError = "暂时无法连接服务，请检查网络或在设置中测试连接"
            } finally { refreshing = false }
        }
    }
    LaunchedEffect(Unit) { sync() }
    LaunchedEffect(settings.lastSync) { if (settings.lastSync.isNotBlank()) syncError = null }
    LaunchedEffect(incomingId, items.size) {
        if (incomingId != null && items.any { it.notice.id == incomingId }) {
            selectedId = incomingId
            repo.editState(incomingId) { it.copy(read = true) }
            consumed()
        }
    }
    fun open(item: NoticeItem) {
        appKeyboard?.hide()
        selectedId = item.notice.id
        scope.launch { repo.editState(item.notice.id) { it.copy(read = true) } }
    }
    fun toggleFavorite(item: NoticeItem) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            repo.editState(item.notice.id) { it.copy(favorite = !it.favorite) }
            if (context.getSharedPreferences("student_feedback", 0).getBoolean("enabled", true))
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            if (item.state.favorite && snackbar.showSnackbar("已取消收藏，已设提醒继续保留", "撤销") == SnackbarResult.ActionPerformed)
                repo.editState(item.notice.id) { it.copy(favorite = true) }
        }
    }
    fun setReminder(item: NoticeItem, deadline: Long?, custom: Boolean) {
        if (deadline == null) {
            chooseDate(context, null) { chosen ->
                if (chosen <= System.currentTimeMillis()) scope.launch { snackbar.showSnackbar("请选择未来的时间") }
                else withPermission {
                    scope.launch {
                        repo.editState(item.notice.id) { it.copy(reminder = true, customDeadline = chosen, sentForDeadline = null) }
                        Reminders.schedule(context, item.notice.id)
                        snackbar.showSnackbar("已设置提醒，默认提前一天通知")
                    }
                }
            }
        } else if (deadline <= System.currentTimeMillis()) {
            scope.launch { snackbar.showSnackbar("该日期已经过去，可自定义新的提醒日期") }
        } else withPermission {
            scope.launch {
                repo.editState(item.notice.id) { it.copy(reminder = true,
                    customDeadline = if (custom) deadline else it.customDeadline, sentForDeadline = null) }
                Reminders.schedule(context, item.notice.id)
                snackbar.showSnackbar("已设置提醒，默认提前一天通知")
            }
        }
    }
    val selected = items.find { it.notice.id == selectedId }
    LaunchedEffect(tab, selectedId) { snackbar.currentSnackbarData?.dismiss() }
    BackHandler(selectedId != null || tab != 0) {
        if (selectedId != null) selectedId = null else tab = 0
    }
    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (selectedId != null) 76.dp else 0.dp)) },
        bottomBar = {
            if (selected == null) IosTabBar(tab) { tab = it }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (selected != null) {
                DetailScreen(selected, onBack = { selectedId = null }, favorite = { toggleFavorite(selected) },
                    reminder = { enabled ->
                        if (enabled) setReminder(selected, selected.deadlineMillis, false)
                        else scope.launch {
                            repo.editState(selected.notice.id) { it.copy(reminder = false) }
                            Reminders.schedule(context, selected.notice.id)
                        }
                    }, custom = {
                        chooseDate(context, selected.deadlineMillis) { chosen -> setReminder(selected, chosen, true) }
                    })
            } else StudentWorkspace(tab, items, settings, ::open, searchNotices = { query = it; category = "全部"; source = "全部"; upcoming = false },
                feed = { FeedScreen(items, query, { query = it }, category, { category = it }, source, { source = it }, upcoming,
                    { upcoming = it }, refreshing, { sync() }, settings, syncError, ::open, ::toggleFavorite) },
                assessment = { ZhcpScreen(settings, repo, snackbar) },
                favorites = { FavoritesScreen(items.filter { it.state.favorite }, ::open, ::toggleFavorite, { tab = 2 }) },
                schedule = { ScheduleScreen(items, ::open, { tab = 2 }) },
                preferences = { SettingsScreen(settings, items.size, refreshing, { sync() },
                    onNews = { enabled -> if (enabled) withPermission { scope.launch { repo.setNews(true) } }
                        else scope.launch { repo.setNews(false) } },
                    onTest = { server -> repo.testConnection(server) },
                    onServer = { server -> repo.setServer(server); sync() }) })
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String, right: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, fontSize = 12.sp, color = Muted)
        }
        right?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedScreen(all: List<NoticeItem>, query: String, onQuery: (String) -> Unit,
                       category: String, onCategory: (String) -> Unit, source: String, onSource: (String) -> Unit,
                       upcoming: Boolean, onUpcoming: (Boolean) -> Unit, refreshing: Boolean, refresh: () -> Unit,
                       settings: Settings, error: String?, open: (NoticeItem) -> Unit, favorite: (NoticeItem) -> Unit) {
    val now = System.currentTimeMillis()
    var unread by rememberSaveable { mutableStateOf(false) }
    var deadlineFilter by rememberSaveable { mutableStateOf("全部日期") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val searchContext = LocalContext.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val searchPreferences = remember { searchContext.getSharedPreferences("notice_searches", 0) }
    var history by remember { mutableStateOf(runCatching {
        val saved = org.json.JSONArray(searchPreferences.getString("items", "[]"))
        (0 until saved.length()).map { saved.getString(it) }
    }.getOrDefault(emptyList())) }
    fun saveHistory(next: List<String>) {
        history = next.take(10)
        searchPreferences.edit().putString("items", org.json.JSONArray(history).toString()).apply()
    }
    val active = all.count { (it.deadlineMillis ?: 0) > now }
    val soon = all.count { (it.deadlineMillis ?: 0) in now..(now + 7 * 86400000L) }
    val filtered = all.filter { item ->
        (category == "全部" || item.notice.category == category) &&
            (source == "全部" || (source == "官网" && item.notice.isOfficial()) || (source == "QQ群" && !item.notice.isOfficial())) &&
            (query.isBlank() || item.notice.title.contains(query.trim(), ignoreCase = true) || item.notice.body.contains(query.trim(), ignoreCase = true)) &&
            (!upcoming || (item.deadlineMillis ?: 0) > now) &&
            (!unread || !item.state.read) &&
            (deadlineFilter == "全部日期" || (deadlineFilter == "已截止" && item.deadlineMillis != null && item.deadlineMillis!! <= now) ||
                (deadlineFilter == "待确认" && item.notice.deadline == null) || (deadlineFilter == "未截止" && (item.deadlineMillis ?: 0) > now))
    }.let { list -> if (upcoming) list.sortedBy { it.deadlineMillis } else list }
    Column {
        PageHeader("竞赛通知", if(unread || deadlineFilter!="全部日期" || source!="全部") "已应用筛选 · 可展开调整" else "最新机会，及时掌握") {
            Row {
                IconButton(onClick={showFilters=!showFilters}) { Icon(Icons.Outlined.Tune,"筛选通知",tint=if(showFilters)Blue else Muted) }
                Surface(shape = CircleShape, color = Color.White) {
                IconButton(onClick = refresh, enabled = !refreshing, modifier = Modifier.testTag("refresh")) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, "刷新通知", tint = Blue)
                }
                }
            }
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxSize().testTag("feed_list")) {
                if(showFilters) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(unread, { unread = !unread }, label = { Text("仅未读") })
                        listOf("全部日期", "未截止", "已截止", "待确认").forEach { label ->
                            FilterChip(deadlineFilter == label, { deadlineFilter = label; onUpcoming(false) }, label = { Text(label) })
                        }
                        TextButton(onClick = { unread = false; deadlineFilter = "全部日期"; onQuery(""); onCategory("全部"); onSource("全部"); onUpcoming(false) }) { Text("重置筛选") }
                    }
                }
                item {
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        IosMetric(all.size.toString(),"收录通知",Modifier.weight(1f),IosInk)
                        IosMetric(active.toString(),"未到截止",Modifier.weight(1f))
                        IosMetric(soon.toString(),"本周截止",Modifier.weight(1f),IosGreen)
                    }
                }

                item {
                    OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (query.isNotBlank()) saveHistory(listOf(query.trim()) + history.filter { it != query.trim() })
                            keyboard?.hide()
                        }),
                        placeholder = { Text("搜索竞赛名称、关键词", fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Muted) },
                        trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "清空搜索") } } } else null,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused=it.isFocused }.testTag("search"),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White,
                            focusedContainerColor = Color.White, unfocusedBorderColor = Color.Transparent, focusedBorderColor = Blue))
                }
                if (history.isNotEmpty() && searchFocused && query.isBlank()) item {
                    Text("最近搜索", fontSize = 12.sp, color = Muted)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(history, key = { it }) { term ->
                            InputChip(selected = false, onClick = { onQuery(term) }, label = { Text(term.take(24)) },
                                trailingIcon = { IconButton(onClick = { saveHistory(history.filter { it != term }) }) { Icon(Icons.Outlined.Close, "删除搜索 $term", Modifier.size(16.dp)) } })
                        }
                        item { TextButton(onClick = { saveHistory(emptyList()) }) { Text("清空记录") } }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("全部", "科技", "创业", "设计", "外语", "综合")) { name ->
                            FilterChip(selected = category == name, onClick = { onCategory(name) }, label = { Text(name, fontSize = 13.sp) },
                                shape = RoundedCornerShape(12.dp), border = null,
                                colors = FilterChipDefaults.filterChipColors(containerColor = Color.White, selectedContainerColor = Blue, selectedLabelColor = Color.White))
                        }
                    }
                    if(showFilters) Spacer(Modifier.height(8.dp))
                    if(showFilters) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("全部来源", "官网", "QQ群")) { name ->
                            val selected = if (name == "全部来源") source == "全部" else source == name
                            FilterChip(selected = selected, onClick = { onSource(if (name == "全部来源") "全部" else name) },
                                label = { Text(name, fontSize = 13.sp) }, shape = RoundedCornerShape(12.dp), border = null,
                                colors = FilterChipDefaults.filterChipColors(containerColor = Color.White,
                                    selectedContainerColor = Color(0xFF149F86), selectedLabelColor = Color.White))
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (query.isNotBlank()) "搜索结果" else if (upcoming) "即将截止" else "最新资讯", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("  ${filtered.size}", fontSize = 13.sp, color = Muted)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onUpcoming(!upcoming) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Icon(if (upcoming) Icons.Outlined.Sort else Icons.Outlined.Schedule, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (upcoming) "按发布查看" else "按截止查看", fontSize = 11.sp)
                        }
                    }
                    Text(if (error != null) error else if (settings.sourceError.isNotBlank() || settings.qqError.isNotBlank())
                        listOf(settings.sourceError, settings.qqError).filter { it.isNotBlank() }.joinToString("  ·  ")
                    else "来源：学校官网 / QQ群  ·  官网 ${syncLabel(settings.sourceSuccess)}",
                        color = if (error != null || settings.sourceError.isNotBlank() || settings.qqError.isNotBlank()) Orange else Muted, fontSize = 11.sp)
                }
                if (filtered.isEmpty()) item {
                    EmptyState(if (refreshing) "正在收集校园机会" else if (all.isEmpty()) "通知还在路上" else "没有找到相关竞赛",
                        if (all.isEmpty()) "联网后下拉刷新，获取学校最新通知" else "试试其他关键词或分类", Icons.Outlined.Search)
                }
                items(filtered, key = { it.notice.id }) { item -> NoticeCard(item, { open(item) }, { favorite(item) }) }
                if (filtered.isNotEmpty()) item {
                    Text("报名安排可能调整，请以通知原文为准", Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        color = Muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.72f))
    }
}

@Composable
private fun NoticeCard(item: NoticeItem, open: () -> Unit, favorite: () -> Unit) {
    val notice = item.notice
    val color = categoryColor(notice.category)
    Surface(onClick = open, shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth().testTag("notice-card-${notice.id}")) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(9.dp), color = color.copy(alpha = 0.09f)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(categoryIcon(notice.category), null, Modifier.size(13.dp), tint = color)
                        Spacer(Modifier.width(4.dp)); Text(notice.category, fontSize = 10.sp, color = color)
                    }
                }
                if (!notice.isOfficial()) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(9.dp), color = Teal.copy(alpha = 0.10f)) {
                        Text("QQ群", Modifier.padding(horizontal = 7.dp, vertical = 4.dp), fontSize = 10.sp, color = Teal)
                    }
                }
                if (!item.state.read) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(5.dp).background(Blue, CircleShape))
                    Spacer(Modifier.width(4.dp)); Text("未读", fontSize = 10.sp, color = Blue)
                }
                if (item.state.changeSummary.isNotBlank()) Text(" · 内容有更新", fontSize = 10.sp, color = Orange)
                Spacer(Modifier.weight(1f))
                Text(notice.publishedAt.replace('-', '.'), color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(notice.title, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            Text(notice.summary.replace('\n', ' '), color = Muted, fontSize = 11.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Background)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                DeadlineLabel(item)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = favorite, modifier = Modifier.size(48.dp)) {
                    Icon(if (item.state.favorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        if (item.state.favorite) "取消收藏" else "收藏通知", Modifier.size(19.dp), tint = if (item.state.favorite) Blue else Muted)
                }
            }
        }
    }
}

@Composable
private fun DeadlineLabel(item: NoticeItem) {
    val deadline = item.deadlineMillis
    val past = deadline != null && deadline <= System.currentTimeMillis()
    val days = deadline?.let { daysUntil(it) }
    val color = if (past || deadline == null) Muted else if (days!! <= 7) Orange else Teal
    val text = when {
        deadline == null -> "截止时间待确认"
        past -> "报名已截止"
        days == 0L -> "今天截止"
        days == 1L -> "明天截止"
        days!! <= 7 -> "$days 天后截止"
        else -> "${dateLabel(deadline, "MM-dd")} 截止"
    }
    Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp), tint = color)
    Spacer(Modifier.width(5.dp))
    Text(text, color = color, fontSize = 11.sp)
    if (item.state.customDeadline != null) Text(" · 自定", color = Muted, fontSize = 10.sp)
}

@Composable
private fun EmptyState(title: String, description: String, icon: ImageVector, action: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).background(Color(0xFFEAF0FF), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = Blue.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(description, color = Muted, fontSize = 12.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) TextButton(onClick = action, modifier = Modifier.padding(top = 10.dp)) { Text("去发现竞赛") }
    }
}

@Composable
private fun ScheduleScreen(all: List<NoticeItem>, open: (NoticeItem) -> Unit, explore: () -> Unit) {
    var onlyMine by rememberSaveable { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val scheduled = all.filter { (it.deadlineMillis ?: 0) > now && (!onlyMine || it.state.reminder) }.sortedBy { it.deadlineMillis }
    Column {
        PageHeader("竞赛日程", "给每一份准备，留出充足时间") {
            Surface(color = Color(0xFFEAF0FF), shape = RoundedCornerShape(14.dp)) {
                Text(LocalDate.now(CHINA).format(DateTimeFormatter.ofPattern("M月d日")), Modifier.padding(12.dp), color = Blue, fontSize = 12.sp)
            }
        }
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = !onlyMine, onClick = { onlyMine = false }, label = { Text("全部日程") })
            FilterChip(selected = onlyMine, onClick = { onlyMine = true }, label = { Text("我的提醒") })
        }
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("${scheduled.size} 项待关注  ·  北京时间", color = Muted, fontSize = 12.sp)
            }
            if (scheduled.isEmpty()) item { EmptyState("为下一场竞赛做好准备", "在通知详情中开启提醒，\n也可以为截止时间待确认的通知自定日期", Icons.Outlined.CalendarMonth, explore) }
            items(scheduled, key = { it.notice.id }) { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(52.dp).padding(top = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dateLabel(item.deadlineMillis!!, "dd"), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Blue)
                        Text(dateLabel(item.deadlineMillis!!, "MM月"), fontSize = 11.sp, color = Muted)
                    }
                    Spacer(Modifier.width(12.dp))
                    Surface(onClick = { open(item) }, shape = RoundedCornerShape(18.dp), color = Color.White, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.notice.category + " · " + dateLabel(item.deadlineMillis!!, "HH:mm") + " 截止", color = categoryColor(item.notice.category), fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(item.notice.title, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (item.state.reminder) {
                                Spacer(Modifier.height(9.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(13.dp), tint = Teal)
                                    Spacer(Modifier.width(4.dp)); Text("已开启提前一天提醒", color = Teal, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(items: List<NoticeItem>, open: (NoticeItem) -> Unit, favorite: (NoticeItem) -> Unit, explore: () -> Unit) {
    Column {
        PageHeader("我的收藏", "${items.size} 份机会，值得认真准备")
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (items.isEmpty()) item { EmptyState("留住心动的竞赛", "点击通知卡片上的收藏图标，\n下次打开就能快速找到", Icons.Outlined.BookmarkBorder, explore) }
            items(items, key = { it.notice.id }) { item -> NoticeCard(item, { open(item) }, { favorite(item) }) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(item: NoticeItem, onBack: () -> Unit, favorite: () -> Unit,
                         reminder: (Boolean) -> Unit, custom: () -> Unit) {
    val context = LocalContext.current
    val notice = item.notice
    val changes = remember(notice.id, notice.updatedAt) { item.state.changeSummary }
    LaunchedEffect(notice.id, notice.updatedAt) {
        if (item.state.changeSummary.isNotBlank()) context.repository().editState(notice.id) { it.copy(changeSummary = "") }
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("通知详情", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${notice.title}\n${notice.url}")
                }, "分享竞赛通知"))
            }) { Icon(Icons.Outlined.Share, "分享") }
        }
        LazyColumn(Modifier.weight(1f).testTag("detail_list"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text(notice.category + " / 竞赛通知", color = categoryColor(notice.category), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (changes.isNotBlank()) Text("本次更新：$changes", color = Orange, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Text(notice.title, fontSize = 23.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text(notice.source, color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp)); Text("${notice.publishedAt} 发布 · 已缓存，可离线阅读", color = Muted, fontSize = 10.sp)
            }
            item {
                Surface(color = Color(0xFFEAF0FF), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.EventAvailable, null, tint = Blue, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (item.state.customDeadline != null) "自定义事项日期（非官方截止）" else "报名截止", color = Blue, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(item.deadlineMillis?.let { dateLabel(it, "yyyy年MM月dd日 HH:mm") } ?: "截止时间待确认", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        if (!notice.deadlineEvidence.isNullOrBlank() && item.state.customDeadline == null) {
                            Spacer(Modifier.height(8.dp))
                            Text("原文依据：${notice.deadlineEvidence}", fontSize = 11.sp, lineHeight = 18.sp, color = Muted)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = custom, contentPadding = PaddingValues(0.dp)) { Text("自定义日期", fontSize = 12.sp) }
                            Spacer(Modifier.weight(1f))
                            Text("提前一天提醒", fontSize = 11.sp, color = Ink)
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = item.state.reminder, onCheckedChange = reminder, modifier = Modifier.testTag("reminder_switch"))
                        }
                        if (item.state.reminder && !Reminders.permitted(context)) {
                            Text("系统通知权限未开启，可在设置中开启", color = Orange, fontSize = 11.sp)
                        }
                    }
                }
            }
            item { Text("通知正文", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            item {
                var offset by rememberSaveable { mutableIntStateOf(1) }
                Text("云端邮箱截止提醒", fontWeight = FontWeight.Medium)
                Text("先在我的 → 邮箱提醒中绑定收件邮箱并开启竞赛截止提醒。", fontSize = 12.sp)
                StudentTabs(listOf("提前一天", "提前三天"), if (offset == 1) 0 else 1) { offset = if (it == 0) 1 else 3 }
                if (notice.deadline != null) OnlineAction("保存此竞赛的邮箱提醒") {
                    val due = Instant.parse(notice.deadline).epochSecond - offset * 86400L
                    context.students().api("reminders/notice-${notice.id}", "PUT", org.json.JSONObject()
                        .put("title", notice.title).put("due", due).put("category", "deadline").put("notice_id", notice.id).put("offset_days", offset))
                    "已同步到服务器；官方截止时间变化时会调整相对提醒"
                } else Text("截止待确认，请到邮箱提醒页添加自定义时间。", fontSize = 12.sp)
            }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(notice.body, Modifier.fillMaxWidth().padding(20.dp), fontSize = 14.sp, lineHeight = 26.sp, color = Color(0xFF46536B))
                    }
                }
            }
            if (notice.imageList().isNotEmpty()) item {
                Text("原文图片", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp)); Text("图片需要联网加载，点击可打开查看", fontSize = 11.sp, color = Muted)
            }
            items(notice.imageList()) { url ->
                Surface(onClick = { openLink(context, url) }, shape = RoundedCornerShape(16.dp), color = Color.White) {
                    SubcomposeAsyncImage(model = url, contentDescription = "通知配图，点击查看原图",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 420.dp).padding(8.dp), contentScale = ContentScale.Fit,
                        loading = { Box(Modifier.height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } },
                        error = { Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Image, null, tint = Muted)
                            Spacer(Modifier.height(8.dp)); Text("图片暂未加载，点击查看原图", fontSize = 12.sp, color = Muted)
                        } })
                }
            }
            if (notice.attachmentList().isNotEmpty()) item { Text("相关附件", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            items(notice.attachmentList()) { (name, url) ->
                Surface(onClick = { openLink(context, url) }, shape = RoundedCornerShape(14.dp), color = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AttachFile, null, tint = Blue, modifier = Modifier.size(20.dp))
                        Text(name, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 12.sp)
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = Muted)
                    }
                }
            }
            item { Text(if (notice.isOfficial()) "信息来自学校官网，报名要求和变更请以原文为准。图片与附件通过原站点查看。"
                else "信息来自QQ群，经关键词筛选并由 AI 复核后收录。报名要求和变更请以群内原文或学校通知为准。",
                fontSize = 11.sp, lineHeight = 19.sp, color = Muted) }
        }
        Surface(color = Color.White, shadowElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = favorite, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(48.dp).weight(1f)) {
                    Icon(if (item.state.favorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (item.state.favorite) "已收藏" else "收藏")
                }
                if (notice.hasWebUrl()) {
                    Button(onClick = { openLink(context, notice.url) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(48.dp).weight(1.7f)) {
                        Text("查看原文"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                    }
                } else {
                    Button(onClick = {}, enabled = false, shape = RoundedCornerShape(14.dp), modifier = Modifier.height(48.dp).weight(1.7f)) {
                        Text("来自QQ群")
                    }
                }
            }
        }
    }
}

@Composable
private fun ZhcpScreen(settings: Settings, repo: Repository, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var role by rememberSaveable { mutableStateOf("student") }
    var account by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(listOf<ZhcpRow>()) }
    var mine by remember { mutableStateOf<ZhcpMine?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && settings.zhcpToken.isNotBlank()) scope.launch {
            loading = true
            try {
                mine = repo.zhcpUpload(settings.zhcpToken, uri)
                snackbar.showSnackbar("材料已提交视觉审核")
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "上传失败")
            } finally { loading = false }
        }
    }
    LaunchedEffect(settings.zhcpToken, settings.zhcpRole) {
        if (settings.zhcpToken.isBlank()) return@LaunchedEffect
        loading = true
        try {
            if (settings.zhcpRole == "secretary") rows = repo.zhcpClass(settings.zhcpToken)
            else mine = repo.zhcpMine(settings.zhcpToken)
        } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: "综测数据加载失败")
        } finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("综测", if (settings.zhcpToken.isBlank()) "团支书用学工登录，学生用教务登录"
            else "${settings.zhcpCollege} ${settings.zhcpClass} · ${settings.zhcpName}")
        if (settings.zhcpToken.isBlank()) {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = role == "student", onClick = { role = "student" }, label = { Text("学生") })
                    FilterChip(selected = role == "secretary", onClick = { role = "secretary" }, label = { Text("团支书") })
                }
                OutlinedTextField(account, { account = it }, label = { Text(if (role == "secretary") "学工账号" else "教务账号") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("密码") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    scope.launch {
                        loading = true
                        try { repo.zhcpLogin(role, account, password); snackbar.showSnackbar("登录成功") }
                        catch (e: Exception) { snackbar.showSnackbar(e.message ?: "登录失败") }
                        finally { loading = false }
                    }
                }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(if (loading) "正在验证…" else "登录")
                }
                Text("密码只发送给综测服务并加密保存，接口不会回传明文。资讯仍走竞赛通。", color = Muted, fontSize = 11.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (settings.zhcpRole == "secretary") "本班进度" else "我的成绩", fontWeight = FontWeight.Bold)
                            if (settings.zhcpRole != "secretary" && mine != null) {
                                Text("总分 ${mine!!.total}  ·  排名 ${mine!!.rank ?: "-"}", color = Blue, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (settings.zhcpRole != "secretary") {
                                    Button(onClick = { picker.launch("*/*") }, enabled = !loading) { Text("上传材料") }
                                } else {
                                    Button(onClick = { openLink(context, settings.zhcpAdminUrl) }) { Text("打开管理端") }
                                }
                                TextButton(onClick = { scope.launch { repo.zhcpLogout() } }) { Text("退出登录") }
                            }
                        }
                    }
                }
                if (settings.zhcpRole == "secretary") {
                    items(rows) { row ->
                        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, fontWeight = FontWeight.Medium)
                                    Text("${row.studentNo}  ·  总分 ${row.total}", color = Muted, fontSize = 11.sp)
                                }
                                Text(if (row.registered) "已注册" else "未注册", color = if (row.registered) Teal else Orange, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    items(mine?.items ?: emptyList()) { item ->
                        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(item.first, fontWeight = FontWeight.Medium)
                                Text(item.second, color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: Settings, count: Int, refreshing: Boolean, refresh: () -> Unit,
                           onNews: (Boolean) -> Unit, onTest: suspend (String) -> Int, onServer: suspend (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var draft by remember(settings.server) { mutableStateOf(settings.server) }
    var testing by remember { mutableStateOf(false) }
    var connectionMessage by remember { mutableStateOf<String?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    if (editing) AlertDialog(onDismissRequest = { if (!testing) editing = false },
        title = { Text("服务地址") }, text = {
            Column {
                Text("填写竞赛通后端地址，保存前会自动测试连接。", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true, label = { Text("地址") }, enabled = !testing)
                if (editError != null) Text(editError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }, confirmButton = { TextButton(enabled = !testing, onClick = {
            testing = true; editError = null
            scope.launch {
                try { onServer(draft); editing = false; connectionMessage = "服务地址已保存" }
                catch (e: Exception) { editError = "连接失败，请检查地址和端口" }
                finally { testing = false }
            }
        }) { Text(if (testing) "正在连接…" else "测试并保存") } }, dismissButton = { TextButton(onClick = { editing = false }, enabled = !testing) { Text("取消") } })
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("使用帮助") },
        text = { Text("• 资讯页下拉即可更新，点击卡片阅读详情。\n\n• 综测页可选团支书（学工）或学生（教务）登录，学生可上传材料，团支书可打开管理端看本班注册与改分。\n\n• 点击收藏，正文就能离线查看。\n\n• 详情页开启提前一天提醒，日期不明确时可自行设置。所有日期均为北京时间。\n\n• 新通知默认关闭，可在本页开启。系统省电或强制停止可能延迟后台更新；重新打开 App 会同步。\n\n• 服务器暂时不可用时，保留手机已有通知。\n\n• 资讯无需账号。综测密码只发给综测服务并加密保存，接口不回传明文。", fontSize = 13.sp, lineHeight = 23.sp) },
        confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
    Column {
        PageHeader("设置", "按你的节奏，发现与准备")
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).background(Blue, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.EmojiEvents, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text("青理竞赛通", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp)); Text("为校园里的每一份热爱", color = Muted, fontSize = 11.sp)
                        }
                        Text("2.0.0", color = Muted, fontSize = 10.sp)
                    }
                }
            }
            item {
                SettingGroup("通知与提醒") {
                    SettingRow(Icons.Outlined.NotificationsActive, "新竞赛通知", "发现新的官网或QQ群通知后合并提醒") {
                        Switch(checked = settings.newsEnabled, onCheckedChange = onNews)
                    }
                    HorizontalDivider(color = Background)
                    SettingRow(Icons.Outlined.NotificationsNone, "系统通知权限", if (Reminders.permitted(context)) "已开启" else "未开启 · 点此设置", {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                    })
                }
            }
            item {
                SettingGroup("同步与连接") {
                    SettingRow(Icons.Outlined.CloudSync, "立即同步", if (refreshing) "正在同步…" else "手机同步：${syncLabel(settings.lastSync)}", { refresh() })
                    HorizontalDivider(color = Background)
                    SettingRow(Icons.Outlined.Dns, "服务地址", settings.server, { draft = settings.server; editError = null; editing = true })
                    HorizontalDivider(color = Background)
                    SettingRow(Icons.Outlined.Wifi, "测试连接", if (testing) "正在测试…" else connectionMessage ?: "检查云端服务是否可用", {
                        if (!testing) scope.launch {
                            testing = true
                            connectionMessage = try { "连接成功 · 云端 ${onTest(settings.server)} 条通知" } catch (e: Exception) { "连接失败，请检查网络或服务地址" }
                            testing = false
                        }
                    })
                }
            }
            item {
                SettingGroup("信息来源") {
                    SettingRow(Icons.Outlined.School, "创新创业学院", "官网更新：${syncLabel(settings.sourceSuccess)}", { openLink(context, "https://chuangye.qut.edu.cn/index/sy/tzgg.htm") })
                    HorizontalDivider(color = Background)
                    SettingRow(Icons.Outlined.Chat, "QQ竞赛群",
                        if (settings.qqError.isNotBlank()) settings.qqError else "最近审核：${syncLabel(settings.qqSuccess)}")
                    HorizontalDivider(color = Background)
                    SettingRow(Icons.AutoMirrored.Outlined.Article, "微信参考文章", "外部原文入口", { openLink(context, WECHAT_REFERENCE) })
                }
                if (settings.sourceError.isNotEmpty()) Text(settings.sourceError, color = Orange, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                SettingGroup("关于") {
                    SettingRow(Icons.AutoMirrored.Outlined.HelpOutline, "使用帮助", "安装、提醒与常见问题", { help = true })
                    HorizontalDivider(color = Background)
                    SettingRow(Icons.Outlined.PhoneAndroid, "本地缓存", "$count 条通知 · 收藏和已读记录保存在本机")
                }
            }
            item {
                Text("个人竞赛信息聚合工具，非学校官方 App。\n无需注册，无广告；报名以学校原文为准。",
                    Modifier.fillMaxWidth(), color = Muted, fontSize = 11.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SettingGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Muted, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White) { Column(content = content) }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String,
                       click: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
        .padding(horizontal = 17.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp)); Text(subtitle, fontSize = 10.sp, color = Muted, lineHeight = 16.sp)
        }
        if (trailing != null) trailing()
        else if (click != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}
