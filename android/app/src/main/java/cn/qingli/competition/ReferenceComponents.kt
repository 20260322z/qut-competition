package cn.qingli.competition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ReferenceBanner(title:String,subtitle:String,icon:ImageVector,content:@Composable ColumnScope.()->Unit={}) {
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFFDDEEFF),Color(0xFFF1F0FF))),RoundedCornerShape(20.dp)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text(title,fontSize=24.sp,lineHeight=30.sp,fontWeight=FontWeight.Bold,color=IosBlue);Text(subtitle,fontSize=12.sp,lineHeight=18.sp,color=IosMuted)}
            Icon(icon,null,tint=IosBlue.copy(alpha=.7f),modifier=Modifier.size(44.dp))
        }
        content()
    }
}

@Composable
fun CompactSearch(value:String,change:(String)->Unit,hint:String,search:()->Unit) {
    OutlinedTextField(value,change,placeholder={Text(hint,fontSize=13.sp)},singleLine=true,
        leadingIcon={Icon(Icons.Outlined.Search,null,Modifier.size(20.dp))},trailingIcon={IconButton(onClick=search){Icon(Icons.Outlined.ArrowCircleRight,"搜索",tint=IosBlue)}},
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),
        shape=RoundedCornerShape(14.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=Color.White,focusedContainerColor=Color.White,unfocusedBorderColor=Color.Transparent),modifier=Modifier.fillMaxWidth())
}

@Composable
fun ChoiceChips(options:List<String>,selected:String,choose:(String)->Unit) {
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {items(options){label->
        FilterChip(selected==label,{choose(label)},label={Text(label,fontSize=12.sp)},shape=RoundedCornerShape(10.dp),
            colors=FilterChipDefaults.filterChipColors(selectedContainerColor=IosBlue,selectedLabelColor=Color.White,containerColor=Color.White),border=null)
    }}
}

@Composable
fun ReferenceEmpty(title:String,body:String,icon:ImageVector=Icons.Outlined.FolderOpen) {
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(26.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(icon,null,tint=IosBlue.copy(alpha=.5f),modifier=Modifier.size(38.dp))
            Text(title,fontSize=16.sp,fontWeight=FontWeight.SemiBold);Text(body,fontSize=12.sp,lineHeight=18.sp,color=IosMuted)
        }
    }
}
