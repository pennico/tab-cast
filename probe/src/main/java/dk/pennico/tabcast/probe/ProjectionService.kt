// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred

/**
 * Android 14 hands out a MediaProjection only to an app running a foreground service of type mediaProjection,
 * started after the user's consent. This service is that, and nothing else: it turns the consent into the
 * MediaProjection and passes it to the activity through [ready].
 */
class ProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("probe", "Probe capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "probe").setSmallIcon(R.drawable.ic_probe)
            .setContentTitle("Tab Cast probe is measuring screen capture").build()
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data ?: error("no consent data"))
                ?: error("getMediaProjection returned null")
            ready?.complete(mp)
        } catch (e: Exception) {
            ready?.completeExceptionally(e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile var ready: CompletableDeferred<MediaProjection>? = null
    }
}
