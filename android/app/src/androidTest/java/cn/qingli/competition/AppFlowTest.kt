package cn.qingli.competition

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.app.NotificationManager

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun searchReadFavoriteAndSettings() {
        val repo = rule.activity.repository()
        rule.waitUntil(timeoutMillis = 90000) {
            runBlocking { repo.dao.allNotices().isNotEmpty() }
        }
        val target = runBlocking { repo.dao.allNotices().first { it.hasWebUrl() } }
        rule.onNodeWithTag("nav-0").performClick()
        rule.onNodeWithTag("search").performTextInput(target.title)
        rule.onNodeWithTag("search").performImeAction()
        rule.onNodeWithTag("feed_list").performScrollToNode(hasTestTag("notice-card-${target.id}"))
        rule.onNodeWithTag("notice-card-${target.id}").performClick()
        rule.onNodeWithText("通知详情").assertIsDisplayed()
        rule.onNodeWithText("查看原文").assertIsDisplayed()
        rule.waitUntil { runBlocking { repo.dao.state(target.id)?.read == true } }
        val state = runBlocking { repo.dao.state(target.id) }
        if (state?.favorite == true) {
            rule.onNodeWithText("已收藏", useUnmergedTree = true).performClick()
            rule.waitUntil(timeoutMillis = 5000) { runBlocking { repo.dao.state(target.id)?.favorite == false } }
        }
        rule.waitUntil(timeoutMillis = 5000) {
            rule.onAllNodesWithText("收藏", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("收藏", useUnmergedTree = true).performClick()
        rule.waitUntil(timeoutMillis = 5000) { runBlocking { repo.dao.state(target.id)?.favorite == true } }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("我的", useUnmergedTree = true).performClick()
        rule.onNodeWithText("我的收藏").performClick()
        rule.onNodeWithText("我的收藏").assertIsDisplayed()
        rule.onNodeWithText(target.title).assertExists()
        rule.onNodeWithTag("nav-1").performClick()
        rule.onNodeWithText("日程提醒").performClick()
        rule.onNodeWithText("统一日程").assertIsDisplayed()
        rule.onNodeWithText("我的", useUnmergedTree = true).performClick()
        rule.onNodeWithText("设置与连接").performScrollTo().performClick()
        rule.onNodeWithText("通知与提醒").assertExists()
        assertTrue(runBlocking { repo.testConnection(DEFAULT_SERVER) } > 0)
    }

    @Test fun syncPreservesFavoritesAndManualReminderDate() = runBlocking {
        val repo = rule.activity.repository()
        repo.sync()
        val notice = repo.dao.allNotices().first()
        val date = System.currentTimeMillis() + 3 * 86400000L
        repo.editState(notice.id) { it.copy(favorite = true, read = true, customDeadline = date) }
        repo.sync()
        val saved = repo.dao.state(notice.id)!!
        assertTrue(saved.favorite)
        assertTrue(saved.read)
        assertEquals(date, saved.customDeadline)
        assertEquals(date, NoticeItem(repo.dao.notice(notice.id)!!, saved).deadlineMillis)
    }

    @Test fun scheduledReminderDeliversAndDoesNotRepeat() {
        val context = rule.activity
        val repo = context.repository()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant cn.qingli.competition android.permission.POST_NOTIFICATIONS")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val item = runBlocking {
            repo.sync()
            repo.dao.allNotices().first()
        }
        val deadline = System.currentTimeMillis() + 3600000L
        runBlocking {
            repo.editState(item.id) { it.copy(reminder = true, customDeadline = deadline, sentForDeadline = null) }
            Reminders.schedule(context, item.id)
        }
        rule.waitUntil(timeoutMillis = 20000) { runBlocking { repo.dao.state(item.id)?.sentForDeadline == deadline } }
        val manager = context.getSystemService(NotificationManager::class.java)
        val first = manager.activeNotifications.first { it.id == item.id.hashCode() }.postTime
        runBlocking { Reminders.schedule(context, item.id) }
        // Let the second real WorkManager task run; a duplicate would change postTime.
        Thread.sleep(3500)
        assertEquals(first, manager.activeNotifications.first { it.id == item.id.hashCode() }.postTime)
        runBlocking {
            repo.editState(item.id) { it.copy(reminder = false, customDeadline = null, sentForDeadline = null) }
            Reminders.schedule(context, item.id)
        }
        manager.cancel(item.id.hashCode())
    }
}
