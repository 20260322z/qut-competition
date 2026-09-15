package cn.qingli.competition

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class StudentFlowTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun competitionDirectoryIsInsideNotificationFilters(){
        rule.onNodeWithText("84项赛事").performClick()
        rule.onNodeWithText("搜索赛事名称").performTextInput("数学建模")
        rule.onNodeWithText("全国大学生数学建模竞赛").performClick()
        rule.onNodeWithTag("search").assertExists()
        rule.onNodeWithText("竞赛目录").assertDoesNotExist()
    }
    @Test fun workbenchHasSingleToolEntriesAndPublishingUsesSheet(){
        rule.onNodeWithTag("nav-1").performClick()
        rule.onAllNodesWithText("简历优化").assertCountEquals(1)
        rule.onAllNodesWithText("模拟面试").assertCountEquals(1)
        rule.onNodeWithText("简历优化").performClick()
        rule.onNodeWithText("简历工作室").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithTag("publish").performClick()
        rule.onNodeWithText("你想发布什么？").assertExists()
        rule.onNodeWithText("分享资料").performClick()
        rule.onNodeWithText("上传资料").assertExists()
        rule.onNodeWithText("选择文件").assertExists()
    }
    @Test fun allPrimaryPagesAndIndependentInterviewOpen(){
        rule.onNodeWithTag("nav-1").performClick()
        rule.onNodeWithText("模拟面试").performClick()
        rule.onNodeWithText("面试训练营").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("资料库").performClick()
        rule.onNodeWithText("我的网盘").performClick()
        rule.onNodeWithText("资料打印").performClick()
        rule.onNodeWithText("选择文件，开始打印准备").assertExists()
        rule.onNodeWithText("打印要求").performClick()
        rule.onNodeWithText("骑马订").assertExists()
        rule.onNodeWithText("完成设置").performClick()
        rule.onNodeWithTag("nav-3").performClick()
        rule.onNodeWithText("赛事提醒").assertExists()
        rule.onNodeWithText("AI任务").performClick()
        rule.onNodeWithText("智能任务中心").assertExists()
        rule.onNodeWithTag("nav-4").performClick()
        rule.onNodeWithText("邮箱提醒").assertExists()
        rule.onNodeWithText("设置与连接").performScrollTo().performClick()
        rule.onNodeWithText("QQ竞赛群").assertDoesNotExist()
    }
    @Test fun localRecordsPersistAndPdfAndDocxAreRealFiles() = runBlocking {
        val repo=rule.activity.students()
        val id=repo.save("task",JSONObject().put("title","测试事项").put("due","2026-12-01"),"instrumentation-test")
        assertEquals("测试事项",repo.dao.get("task",id)!!.data().getString("title"))
        val pdf=makePdf("测试简历","负责课程项目的数据整理。\n这是第二段内容。")
        assertTrue(pdf.toString(Charsets.ISO_8859_1).startsWith("%PDF"))
        val docx=makeDocx("测试简历","真实经历")
        val zip=java.util.zip.ZipInputStream(docx.inputStream());val entries=mutableListOf<String>()
        while(true){val entry=zip.nextEntry?:break;entries+=entry.name}
        assertTrue("word/document.xml" in entries)
        repo.dao.delete("task",id)
    }
    @Test fun gradeImportUndoPreservesExistingAndRejectsConflict()=runBlocking{
        val dao=rule.activity.students().dao
        val old=StudentRecord("grade","test-grade-existing",JSONObject().put("score",80).toString())
        dao.put(old)
        val changed=old.copy(json=JSONObject().put("score",90).toString())
        val added=StudentRecord("grade","test-grade-added",JSONObject().put("score",70).toString())
        dao.mergeGrades(listOf(changed,added));dao.undoGradeImport()
        assertEquals(old.json,dao.get("grade",old.id)!!.json);assertNull(dao.get("grade",added.id))
        dao.mergeGrades(listOf(changed));dao.put(old.copy(json="{\"score\":95}"))
        assertTrue(runCatching{dao.undoGradeImport()}.isFailure)
        assertEquals(95,dao.get("grade",old.id)!!.data().getInt("score"))
        dao.delete("grade",old.id);dao.delete("draft","grade-import-backup")
    }
    @Test fun accountSwitchDoesNotExposePrivateDrafts()=runBlocking{
        val repo=rule.activity.students()
        org.junit.Assume.assumeTrue(repo.account.value.isBlank())
        fun session(id:String)=JSONObject().put("id",id).put("email","$id@example.com").put("token",java.util.UUID.randomUUID().toString())
        try{
            repo.login(session("instrumentation-a"));repo.save("draft",JSONObject().put("text","仅属于账号 A 的合成测试"),"privacy-test")
            repo.login(session("instrumentation-b"));assertNull(repo.dao.get("draft","privacy-test"))
            repo.login(session("instrumentation-a"));assertEquals("仅属于账号 A 的合成测试",repo.dao.get("draft","privacy-test")!!.data().getString("text"))
            repo.dao.delete("draft","privacy-test")
        }finally{repo.logout()}
        assertNull(repo.dao.get("draft","privacy-test"))
    }
    @Test fun transcriptValidationAndReplaceUndo()=runBlocking{
        val good=JSONObject().put("semester","2025-12").put("course","实践课").put("credits",2).put("score","优秀").put("gpa",4).put("status","正常")
        val grades=parseAcademicTranscript(org.json.JSONArray().put(good))
        assertNull(grades[0].score);assertEquals(4.0,summarizeGrades(grades).gpa!!,0.001)
        assertTrue(runCatching{parseAcademicTranscript(org.json.JSONArray().put(good).put(JSONObject(good.toString()).put("course","bad").put("credits","NaN")))}.isFailure)
        assertEquals("未发布",parseAcademicTranscript(org.json.JSONArray().put(JSONObject(good.toString()).put("score","--")))[0].status)
        val db=androidx.room.Room.inMemoryDatabaseBuilder(rule.activity,StudentDatabase::class.java).build()
        try {
            val dao=db.dao();val old=StudentRecord("grade","old","{\"score\":80}");dao.put(old)
            val new=StudentRecord("grade","new",grades[0].json().toString());dao.replaceGrades(listOf(new))
            assertNull(dao.get("grade","old"));dao.undoGradeImport();assertEquals(old.json,dao.get("grade","old")!!.json);assertNull(dao.get("grade","new"))
        } finally {db.close()}
    }
}
