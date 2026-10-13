package com.hermes.llama

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Stage (d): foreground service that keeps the embedded OpenAI-compatible
 * server alive while the app is backgrounded.
 *
 * A single model maps to a single server → many clients. The actual socket
 * server lives in [LocalServer]; this service only owns its lifecycle, the
 * notification, and the "started/stopped" signal back to the UI.
 */
class ServerService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LocalServer.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                ServerState.set(false, 0)
                return START_NOT_STICKY
            }
            else -> {
                val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
                LocalServer.bindAll = intent?.getBooleanExtra(EXTRA_BIND_ALL, false) ?: false
                LocalServer.apiKey = intent?.getStringExtra(EXTRA_KEY)?.takeIf { it.isNotBlank() }

                val auth = LocalServer.apiKey != null
                startForeground(NOTIF_ID, buildNotification(port, auth))
                val ok = LocalServer.start(port)
                ServerState.set(ok, if (ok) port else 0)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LocalServer.stop()
        ServerState.set(false, 0)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Local model server",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun buildNotification(port: Int, auth: Boolean): Notification {
        val host = if (LocalServer.bindAll) "0.0.0.0" else "127.0.0.1"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Llama server running")
            .setContentText("http://$host:$port/v1${if (auth) " · auth on" else ""}")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "hermes_server"
        private const val NOTIF_ID = 42
        private const val DEFAULT_PORT = 8080

        const val ACTION_START = "com.hermes.llama.server.START"
        const val ACTION_STOP = "com.hermes.llama.server.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_BIND_ALL = "bind_all"
        const val EXTRA_KEY = "api_key"

        /** Start (or restart) the server as a foreground service. */
        fun start(context: Context, port: Int, bindAll: Boolean, key: String?) {
            val i = Intent(context, ServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_BIND_ALL, bindAll)
                putExtra(EXTRA_KEY, key)
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}

/** Tiny observable holder so the UI can reflect the live server state. */
object ServerState {
    @Volatile var running: Boolean = false
        private set
    @Volatile var port: Int = 0
        private set

    fun set(running: Boolean, port: Int) {
        this.running = running
        this.port = port
    }
}