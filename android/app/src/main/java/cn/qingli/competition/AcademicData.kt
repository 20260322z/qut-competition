package cn.qingli.competition

import org.json.JSONArray

fun parseAcademicTranscript(rows:JSONArray):List<Grade> {
    require(rows.length() in 1..500){"未取得完整有效的成绩单，原成绩保留"}
    require(rows.objects().size==rows.length()){ "成绩单存在无法识别的记录，原成绩保留" }
    val result=rows.objects().map { r ->
        val credits=r.optString("credits").toDoubleOrNull()
        val raw=r.optString("score").trim();val score=raw.toDoubleOrNull()
        val rawGp=if(r.isNull("gpa"))"" else r.optString("gpa").trim();val gp=rawGp.toDoubleOrNull()
        require(credits!=null&&credits.isFinite()&&credits in 0.0..50.0){"存在无法识别的学分，请核对教务原表"}
        require(score==null||(score.isFinite()&&score in 0.0..100.0)){"存在无效分数，未保存"}
        require(rawGp.isBlank()||(gp!=null&&gp.isFinite()&&gp in 0.0..5.0)){"存在无法识别的绩点，未保存"}
        val term=r.optString("semester");val name=r.optString("course")
        require(term.isNotBlank()&&name.isNotBlank()){ "学期或课程缺失，未保存" }
        val status=r.optString("status","正常").let{if(it=="正常" && raw in listOf("","null","--","未发布","待发布"))"未发布" else it}
        require(status in listOf("正常","未发布","补考","重修","免修","不计入")){"课程状态需要核对，未保存"}
        Grade(term,name,credits,score,gp,status,"教务原始成绩：${raw.ifBlank{"待发布"}}")
    }.distinct()
    require(result.size==rows.length() || result.isNotEmpty())
    require(result.map{it.semester to it.course}.distinct().size==result.size){"同学期存在同名课程的不同记录，请在文件导入中核对后再保存"}
    return result
}

fun semesterOrder(value:String):Int {
    val year=Regex("\\d{4}").find(value)?.value?.toIntOrNull()?:0
    val suffix=value.substringAfter('-',"")
    val term=when(suffix){"3","1","第一学期","一学期"->1;"12","2","第二学期","二学期"->2;"16","第三学期","三学期"->3;else->if(value.contains("第二"))2 else 1}
    return year*10+term
}

fun semesterLabel(value:String):String {
    val match=Regex("^(\\d{4})-(3|12|16|1|2|第?[一二三]学期)$").matchEntire(value)?:return value
    val year=match.groupValues[1].toInt();val term=when(match.groupValues[2]){"3","1","第一学期","一学期"->"第一学期";"12","2","第二学期","二学期"->"第二学期";else->"短学期"}
    return "$year-${year+1} $term"
}
