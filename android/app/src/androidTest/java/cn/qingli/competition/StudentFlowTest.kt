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
    @Test fun globalSearchFindsContestWithoutLogin(){
        rule.onNodeWithText("搜索工作台").performScrollTo().performClick()
        rule.onNodeWithText("赛事、通知、课程、资料或经历关键词").performTextInput("数学建模")
        rule.onNodeWithText("全国大学生数学建模竞赛").performScrollTo().performClick()
        rule.onNodeWithText("赛事详情").assertExists()
        rule.onNodeWithText("赛事官方入口").assertExists()
    }
    @Test fun fiveDestinationsAndOfflineToolsOpen() {
        rule.onNodeWithText("今天，向目标近一步").assertExists()
        rule.onNodeWithText("学业",useUnmergedTree=true).performClick()
        rule.onNodeWithText("成绩与课程分析").assertExists()
        rule.onNodeWithText("成长档案",useUnmergedTree=true).performClick()
        rule.onNodeWithText("添加经历").assertExists()
        rule.onNodeWithText("竞赛",useUnmergedTree=true).performClick()
        rule.onNodeWithTag("search").assertExists()
        rule.onNodeWithText("升学",useUnmergedTree=true).performClick()
        rule.onNodeWithText("我的目标院校").assertExists()
        rule.onNodeWithText("简历工作室").performScrollTo().performClick()
        rule.onNodeWithText("把真实经历，讲得更清楚").assertExists()
        rule.onNodeWithText("编辑",useUnmergedTree=true).performClick()
        rule.onNodeWithText("保存为本机新版本").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("我的",useUnmergedTree=true).performClick()
        rule.onNodeWithText("个人信息与目标").performClick()
        rule.onNodeWithText("我的方向").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("资料中心").performScrollTo().performClick()
        rule.onNodeWithText("公共资料").assertIsDisplayed()
        rule.onNodeWithText("私人文件").performClick()
        rule.onNodeWithText("打印准备").performClick()
        rule.onNodeWithText("选择 1—20 个文件").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("设置与连接").assertExists()
    }
    @Test fun interviewAndContestHaveSeparateWorkspaces(){
        rule.onNodeWithText("升学",useUnmergedTree=true).performClick()
        rule.onNodeWithText("面试练习").performScrollTo().performClick()
        rule.onNodeWithText("面试训练营").assertExists()
        rule.onNodeWithText("3 题热身",useUnmergedTree=true).performClick()
        rule.onNodeWithText("复盘记录",useUnmergedTree=true).performClick()
        rule.onNodeWithText("任务记录").assertExists()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("竞赛",useUnmergedTree=true).performClick()
        rule.onNodeWithText("竞赛目录",useUnmergedTree=true).performClick()
        rule.onNodeWithText("赛事中心").assertExists()
        rule.onNodeWithText("搜索赛事名称、简称或旧名").performTextInput("数学建模")
        rule.onNodeWithText("全国大学生数学建模竞赛").performScrollTo().performClick()
        rule.onNodeWithText("赛事详情").assertExists()
        rule.onNodeWithText("赛事官方入口").assertExists()
        rule.onNodeWithText("报名平台（核对当届开放状态）").assertExists()
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
}
