package cn.qingli.competition

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// iOS reference rhythm, adapted to Android fonts, insets and 48 dp touch targets.
val IosBlue = Color(0xFF007AFF)
val IosBackground = Color(0xFFF2F2F7)
val IosInk = Color(0xFF1C1C1E)
val IosMuted = Color(0xFF68686F)
val IosSeparator = Color(0xFFE5E5EA)
val IosGreen = Color(0xFF248A3D)

@Composable
fun QingliTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary=IosBlue, onPrimary=Color.White, primaryContainer=Color(0xFFE7F1FF), onPrimaryContainer=Color(0xFF004EA2),
            secondary=IosGreen, secondaryContainer=Color(0xFFE8F5EC), onSecondaryContainer=Color(0xFF165C2A),
            background=IosBackground, onBackground=IosInk, surface=Color.White, onSurface=IosInk,
            onSurfaceVariant=IosMuted, surfaceVariant=Color(0xFFEEEEF3), surfaceContainer=Color.White,
            outline=Color(0xFFC7C7CC), outlineVariant=IosSeparator, error=Color(0xFFD70015), surfaceTint=Color.Transparent),
        typography=base.copy(
            headlineLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=32.sp,lineHeight=39.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.7).sp),
            headlineMedium=TextStyle(fontSize=27.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.4).sp),
            titleLarge=TextStyle(fontSize=21.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold),
            titleMedium=TextStyle(fontSize=17.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold),
            bodyLarge=TextStyle(fontSize=16.sp,lineHeight=24.sp),
            bodyMedium=TextStyle(fontSize=14.sp,lineHeight=21.sp),
            labelLarge=TextStyle(fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium)),
        shapes=Shapes(extraSmall=RoundedCornerShape(7.dp),small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(14.dp),large=RoundedCornerShape(18.dp),extraLarge=RoundedCornerShape(24.dp)),
        content=content)
}

fun Modifier.iosClick(onClick: () -> Unit): Modifier = composed {
    val source=remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed) .985f else 1f, tween(130),label="press")
    graphicsLayer { scaleX=scale;scaleY=scale;alpha=if(pressed) .78f else 1f }
        .clickable(interactionSource=source,indication=null,role=Role.Button,onClick=onClick)
}

@Composable
fun IosTabBar(selected: Int, select: (Int)->Unit) {
    val view=LocalView.current
    val context=LocalContext.current
    Surface(color=Color.White.copy(alpha=.97f)) {
        Column {
            HorizontalDivider(thickness=.5.dp,color=IosSeparator)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=6.dp,vertical=4.dp)) {
                listOf("首页" to Icons.Outlined.Home,"学业" to Icons.Outlined.School,"竞赛" to Icons.Outlined.EmojiEvents,
                    "升学" to Icons.Outlined.AutoStories,"我的" to Icons.Outlined.PersonOutline).forEachIndexed { index,(label,icon) ->
                    val active=selected==index
                    Column(Modifier.weight(1f).heightIn(min=54.dp).clip(RoundedCornerShape(12.dp))
                        .selectable(active,role=Role.Tab,onClick={
                            if(!active && context.getSharedPreferences("student_feedback",0).getBoolean("enabled",true))
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            select(index)
                        }),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        Icon(icon,null,Modifier.size(25.dp),tint=if(active) IosBlue else Color(0xFF85858B))
                        Spacer(Modifier.height(3.dp))
                        Text(label,fontSize=11.sp,lineHeight=14.sp,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal,color=if(active) IosBlue else IosMuted)
                    }
                }
            }
        }
    }
}

@Composable
fun IosIcon(icon: ImageVector, tint: Color=IosBlue, size: Int=36) {
    Box(Modifier.size(size.dp).background(tint.copy(alpha=.10f),RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) {
        Icon(icon,null,Modifier.size((size*.55f).dp),tint=tint)
    }
}

@Composable
fun IosSection(title: String, action: String="", onClick: ()->Unit={}) {
    Row(Modifier.fillMaxWidth().padding(top=2.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),fontSize=17.sp,lineHeight=23.sp,fontWeight=FontWeight.SemiBold)
        if(action.isNotBlank())TextButton(onClick=onClick,contentPadding=PaddingValues(horizontal=4.dp)) { Text(action,fontSize=14.sp) }
    }
}

@Composable
fun IosGroup(content: @Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=Color.White) { Column(content=content) }
}

@Composable
fun IosRow(title: String, subtitle: String="", icon: ImageVector=Icons.Outlined.ChevronRight,
           tint: Color=IosBlue, divider: Boolean=true, onClick: ()->Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).iosClick(onClick).padding(horizontal=13.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            IosIcon(icon,tint,size=30)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(title,fontSize=15.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium,color=IosInk,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(subtitle.isNotBlank())Text(subtitle,fontSize=12.sp,lineHeight=17.sp,color=IosMuted,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(18.dp),tint=Color(0xFFB6B6BC))
        }
        if(divider)HorizontalDivider(Modifier.padding(start=53.dp),thickness=.5.dp,color=IosSeparator)
    }
}

@Composable
fun IosMetric(value: String, label: String, modifier: Modifier=Modifier, tint: Color=IosBlue) {
    Surface(modifier,shape=RoundedCornerShape(14.dp),color=Color.White) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(label,fontSize=11.sp,color=IosMuted)
            Text(value,fontSize=23.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold,color=tint,letterSpacing=(-.4).sp)
        }
    }
}

data class IosTool(val title: String,val icon: ImageVector,val route: String)

@Composable
fun IosToolGrid(tools: List<IosTool>, columns: Int=4, open: (String)->Unit) {
    IosGroup {
        Column(Modifier.padding(5.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            tools.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { tool ->
                        Column(Modifier.weight(1f).heightIn(min=70.dp).clip(RoundedCornerShape(10.dp)).iosClick{open(tool.route)}.padding(horizontal=3.dp,vertical=10.dp),
                            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)) {
                            Icon(tool.icon,null,Modifier.size(23.dp),tint=IosBlue)
                            Text(tool.title,fontSize=12.sp,lineHeight=17.sp,fontWeight=FontWeight.Medium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    repeat(columns-row.size){Spacer(Modifier.weight(1f))}
                }
            }
        }
    }
}

@Composable
fun IosQuickAction(title: String, subtitle: String, icon: ImageVector, modifier: Modifier=Modifier, onClick: ()->Unit) {
    Surface(modifier.iosClick(onClick),shape=RoundedCornerShape(18.dp),color=Color.White) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Icon(icon,null,Modifier.size(24.dp),tint=IosBlue)
            Text(title,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            Text(subtitle,fontSize=12.sp,lineHeight=17.sp,color=IosMuted)
        }
    }
}
