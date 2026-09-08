package cn.qingli.competition

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class QutApplication : Application() {
    val repository by lazy { Repository(this) }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("news", "新竞赛通知", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("deadlines", "报名截止提醒", NotificationManager.IMPORTANCE_HIGH))
        val work = WorkManager.getInstance(this)
        work.enqueueUniquePeriodicWork("qut-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        work.enqueueUniquePeriodicWork("qut-reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.HOURS).build())
    }
}

fun Context.repository() = (applicationContext as QutApplication).repository

object Reminders {
    fun permitted(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun post(context: Context, channel: String, id: Int, title: String, body: String, noticeId: String? = null): Boolean {
        if (!permitted(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (noticeId != null) putExtra("notice_id", noticeId)
        }
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true).build()
        return runCatching { manager.notify(id, notification); true }.getOrDefault(false)
    }

    suspend fun schedule(context: Context, id: String) {
        val repo = context.repository()
        val state = repo.dao.state(id) ?: return
        val notice = repo.dao.notice(id) ?: return
        val deadline = NoticeItem(notice, state).deadlineMillis
        val work = WorkManager.getInstance(context)
        val key = "deadline-$id"
        if (!state.reminder || deadline == null || deadline <= System.currentTimeMillis()) {
            work.cancelUniqueWork(key)
            return
        }
        val delay = (deadline - TimeUnit.DAYS.toMillis(1) - System.currentTimeMillis()).coerceAtLeast(0)
        work.enqueueUniqueWork(key, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(workDataOf("notice_id" to id)).setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
    }

    suspend fun announceNews(context: Context, result: SyncResult) {
        if (!result.initial && result.added.isNotEmpty() && context.repository().settings.first().newsEnabled) {
            post(context, "news", 100, "发现 ${result.added.size} 条新竞赛通知", result.added.take(3).joinToString("\n") { it.title })
        }
    }
}

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        Reminders.announceNews(applicationContext, applicationContext.repository().sync())
        Result.success()
    } catch (e: Exception) { Result.retry() }
}

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repo = applicationContext.repository()
        val target = inputData.getString("notice_id")
        for (state in repo.dao.reminders().filter { target == null || target == it.id }) {
            val notice = repo.dao.notice(state.id) ?: continue
            val deadline = NoticeItem(notice, state).deadlineMillis ?: continue
            val now = System.currentTimeMillis()
            if (now >= deadline - TimeUnit.DAYS.toMillis(1) && now < deadline && state.sentForDeadline != deadline) {
                val posted = Reminders.post(applicationContext, "deadlines", state.id.hashCode(),
                    "报名即将截止", notice.title, state.id)
                if (posted) repo.editState(state.id) { current -> current.copy(sentForDeadline = deadline) }
            }
        }
        return Result.success()
    }
}
