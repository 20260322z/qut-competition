package cn.qingli.competition

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Synthetic fixtures only: nothing is posted to the public service. */
@RunWith(AndroidJUnit4::class)
class ReferenceVisualTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun page(content:@Composable ()->Unit){rule.activity.runOnUiThread{rule.activity.setContent{QingliTheme{Surface(color=IosBackground,modifier=Modifier.fillMaxSize()){Column{Text("合成示例 · 仅用于界面验证");content()}}}}};rule.waitForIdle()}
    private fun shot(name:String){
        rule.waitForIdle()
        val dir=File(rule.activity.getExternalFilesDir(null),"reference-preview").apply{mkdirs()}
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let{bitmap->File(dir,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    @Test fun teamAndExperienceUseSamePostLayout(){
        val repo=rule.activity.students()
        val team=JSONObject().put("title","数学建模，一起把想法做出来").put("contest","全国大学生数学建模竞赛").put("status","open").put("members",1).put("capacity",3).put("owner","example").put("data",JSONObject().put("roles","寻找一位会 Python 的同学和一位写作伙伴。已经整理好历年题目，周末一起练习。").put("location","线上协作"))
        page{CommunityReference(repo,true,listOf(team),"",{},"",{},false,{},"",{},{})}
        rule.onNodeWithText("数学建模，一起把想法做出来").assertExists();shot("找队友社区")
        val post=JSONObject().put("title","第一次参加数模，我想分享的三件事").put("contest","全国大学生数学建模竞赛").put("owner","example").put("year","2026").put("body","先练完整流程，再补工具。每天写一份进展记录，约定好分工，遇到问题及时和队友沟通。")
        page{CommunityReference(repo,false,listOf(post),"",{},"",{},false,{},"",{},{})}
        rule.onNodeWithText("第一次参加数模，我想分享的三件事").assertExists();shot("经验交流社区")
    }
    @Test fun sharedSpaceOpensCategoryAndTasksUseSheet(){
        val files=listOf("数学建模入门.pdf","论文排版模板.docx","题目复盘笔记.pdf").map{JSONObject().put("name",it).put("size",20480).put("data",JSONObject().put("category","数学建模"))}
        page{StudentPage(""){FileSpaceReference(true,files,"",{},{},{},{})}}
        rule.onNodeWithText("3 个已加载文件").assertExists();shot("资料共享空间")
        rule.onNodeWithText("进入空间 →").performClick();rule.onNodeWithText("数学建模入门.pdf").assertExists()
        page{RecordScreen(rule.activity.students(),emptyList(),"task","待办安排",listOf("title" to "任务名称","note" to "备注","due" to "日期","source" to "来源"))}
        rule.onNodeWithText("添加记录").performClick();rule.onNodeWithText("准备做什么？").assertExists();rule.onNodeWithText("保存").assertIsDisplayed();shot("待办快速发布")
    }
    @Test fun gradesShowTermsAndPerCourseScores(){
        val records=listOf(Grade("2025-3","高等数学",4.0,80.0,3.0,"正常"),Grade("2025-12","大学英语",2.0,90.0,4.0,"正常")).mapIndexed{i,g->StudentRecord("grade","example-$i",g.json().toString())}
        page{AcademicDashboard(rule.activity.students(),records,{}, {})}
        rule.onAllNodesWithText("2025-2026 第二学期")[0].assertExists();rule.onNodeWithText("大学英语").performScrollTo().assertExists();shot("学期成绩分析")
    }
}
