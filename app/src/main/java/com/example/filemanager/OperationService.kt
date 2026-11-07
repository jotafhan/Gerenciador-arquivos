package com.example.filemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * Serviço em primeiro plano que acompanha a operação em andamento (compactar/extrair):
 * mostra o progresso na barra de notificações, mantém o app vivo enquanto você usa outros
 * aplicativos e avisa (com som) quando termina.
 */
class OperationService : Service() {

    @Volatile private var running = false
    @Volatile private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Operações em andamento", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Operações concluídas", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            ActiveTask.current?.second?.cancelled = true
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        val task = ActiveTask.current
        ServiceCompat.startForeground(
            this,
            PROGRESS_ID,
            progressNotification(task?.first ?: "Operação em andamento", task?.second),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (!running) {
            running = true
            Thread { loop() }.apply { isDaemon = true }.start()
        }
        return START_NOT_STICKY
    }

    private fun loop() {
        val manager = NotificationManagerCompat.from(this)
        while (true) {
            val task = ActiveTask.current ?: break
            runCatching { manager.notify(PROGRESS_ID, progressNotification(task.first, task.second)) }
            try {
                Thread.sleep(700)
            } catch (e: InterruptedException) {
                break
            }
        }
        running = false
        val message = ActiveTask.lastMessage
        if (message != null) notifyDone(message)
        Handler(Looper.getMainLooper()).post {
            // Só encerra se nenhuma operação nova começou nesse meio tempo
            if (ActiveTask.current == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(latestStartId)
            }
        }
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun progressNotification(title: String, progress: OpProgress?): Notification {
        val total = progress?.total ?: 0L
        val done = progress?.done ?: 0L
        val percent = if (total > 0) ((done * 100) / total).coerceIn(0L, 100L).toInt() else 0
        val remaining = progress?.remainingMs()
        val text = buildString {
            append(if (total > 0) "$percent%" else "Em andamento")
            if (remaining != null) append("  •  restam ~").append(formatClock(remaining))
            val label = progress?.label.orEmpty()
            if (label.isNotEmpty()) append("\n").append(label)
        }
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, OperationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancelar", cancel)
            .build()
    }

    private fun notifyDone(message: String) {
        val tookAWhile = ActiveTask.lastElapsedMs >= 3000L // operações rápidas não precisam de som
        val ok = ActiveTask.lastOk
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (ok) "Concluído" else "Operação não concluída")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setSilent(!tookAWhile)
            .build()
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(DONE_ID, notification) }
        } else if (tookAWhile) {
            // Sem permissão de notificação: toca o som de aviso direto
            runCatching {
                RingtoneManager.getRingtone(
                    applicationContext,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                )?.play()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.example.filemanager.CANCEL_OPERATION"
        private const val CHANNEL_PROGRESS = "ops_progress"
        private const val CHANNEL_DONE = "ops_done"
        private const val PROGRESS_ID = 1001
        private const val DONE_ID = 1002
    }
}
