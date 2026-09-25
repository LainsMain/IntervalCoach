package com.intervalcoach.session

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.intervalcoach.CoachApp
import com.intervalcoach.MainActivity
import com.intervalcoach.R
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

class WorkoutService : Service() {
    companion object {
        const val ACTION_START = "com.intervalcoach.START"
        const val ACTION_PAUSE = "com.intervalcoach.PAUSE"
        const val ACTION_RESUME = "com.intervalcoach.RESUME"
        const val ACTION_NEXT = "com.intervalcoach.NEXT"
        const val ACTION_PREVIOUS = "com.intervalcoach.PREVIOUS"
        const val ACTION_STOP = "com.intervalcoach.STOP"
        const val EXTRA_WORKOUT_ID = "workout_id"
        private const val CHANNEL = "active_workout"
        private const val NOTIFICATION_ID = 1
        fun command(context: Context, action: String, workoutId: Long = 0) {
            val intent = Intent(context, WorkoutService::class.java).setAction(action).putExtra(EXTRA_WORKOUT_ID, workoutId)
            if (action == ACTION_START) context.startForegroundService(intent) else context.startService(intent)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = SessionEngine { SystemClock.elapsedRealtime() }
    private lateinit var speech: SpeechCoach
    private lateinit var power: PowerManager.WakeLock
    private val prefs by lazy { getSharedPreferences("session", MODE_PRIVATE) }
    private val app get() = application as CoachApp
    private var loop: Job? = null
    private var lastNotificationSecond = -1
    private var lastPersistSignature = ""
    private var startedForeground = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Active workout", NotificationManager.IMPORTANCE_LOW))
        speech = SpeechCoach(this)
        power = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntervalCoach:Workout")
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            speech.onIdle = null
            val id = intent.getLongExtra(EXTRA_WORKOUT_ID, 0)
            // Fulfil the foreground-service start contract before the database read.
            if (!startedForeground) foreground(notification(null))
            scope.launch {
                val workout = app.repository.get(id)
                if (workout == null || workout.intervals.isEmpty()) { stopSession(); return@launch }
                speech.interrupt()
                apply(engine.start(id, workout.workout.name, workout.intervals.map { SessionInterval(it.activity, it.durationSeconds, it.instruction) }), interrupt = false)
                startLoop()
            }
        } else {
            if (engine.snapshot == null) restore()
            when (intent?.action) {
                ACTION_PAUSE -> { speech.interrupt(); apply(engine.pause()) }
                ACTION_RESUME -> apply(engine.resume())
                ACTION_NEXT -> { speech.interrupt(); apply(engine.next()); startLoop() }
                ACTION_PREVIOUS -> { speech.interrupt(); apply(engine.previous()); startLoop() }
                ACTION_STOP -> stopSession()
                null -> { if (engine.snapshot == null) stopSession() else startLoop() }
            }
        }
        return START_STICKY
    }
    private fun startLoop() {
        loop?.cancel()
        if (engine.snapshot?.complete == true) return
        if (!power.isHeld) power.acquire(6 * 60 * 60 * 1000L)
        loop = scope.launch {
            while (isActive && engine.snapshot != null && engine.snapshot?.complete == false) {
                apply(engine.tick())
                delay(200L)
            }
        }
    }
    private fun apply(update: EngineUpdate, interrupt: Boolean = false) {
        val s = update.snapshot ?: return
        app.session.value = s
        val signature = "${s.index}:${s.targetMs}:${s.paused}:${s.complete}:${s.tenSpoken}:${s.countdownSpoken}"
        if (signature != lastPersistSignature) { persist(s); lastPersistSignature = signature }
        if (interrupt) speech.interrupt()
        if (s.complete) foreground(notification(s))
        update.cues.forEach { cue ->
            when (cue) {
                is SessionCue.Announce -> speech.say(announcement(cue.interval, cue.next))
                SessionCue.TenSeconds -> speech.say("10 seconds remaining.")
                is SessionCue.Count -> speech.say(cue.number.toString())
                SessionCue.Done -> { speech.interrupt(); speech.onIdle = { scope.launch { if (engine.snapshot?.complete == true) { stopForeground(STOP_FOREGROUND_REMOVE); startedForeground = false; stopSelf() } } }; speech.say("Exercise done.") }
            }
        }
        val seconds = ceil(s.remainingMs / 1000.0).toInt()
        if (seconds != lastNotificationSecond && (seconds % 5 == 0 || s.paused || s.complete || update.cues.isNotEmpty())) {
            lastNotificationSecond = seconds
            if (!s.complete) foreground(notification(s))
        }
        if (s.complete) {
            loop?.cancel()
            if (power.isHeld) power.release()
        }
    }
    private fun foreground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
        startedForeground = true
    }
    private fun notification(s: SessionSnapshot?): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (s?.complete == true) "Exercise done" else s?.let { "${it.current.activity.uppercase()}${it.current.instruction.takeIf(String::isNotBlank)?.let { instruction -> " · $instruction" } ?: ""}" } ?: "Starting workout")
            .setContentText(if (s?.complete == true) "Workout complete" else s?.let { if (it.paused) "Paused · ${formatTime(it.remainingMs)} remaining" else "${formatTime(it.remainingMs)} remaining" } ?: "Preparing intervals")
            .setContentIntent(launch).setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (s != null && !s.complete) {
            builder.addAction(0, if (s.paused) "Resume" else "Pause", action(if (s.paused) ACTION_RESUME else ACTION_PAUSE, 1))
            builder.addAction(0, "Next", action(ACTION_NEXT, 2))
            builder.addAction(0, "Stop", action(ACTION_STOP, 3))
        }
        return builder.build()
    }
    private fun action(value: String, code: Int): PendingIntent = PendingIntent.getService(this, code, Intent(this, WorkoutService::class.java).setAction(value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun persist(s: SessionSnapshot) {
        val json = JSONObject().put("workoutId", s.workoutId).put("name", s.workoutName).put("index", s.index)
            .put("remainingMs", s.remainingMs).put("targetMs", s.targetMs).put("paused", s.paused).put("complete", s.complete)
            .put("tenSpoken", s.tenSpoken).put("counts", JSONArray(s.countdownSpoken.toList()))
            .put("intervals", JSONArray().apply { s.intervals.forEach { put(JSONObject().put("activity", it.activity).put("seconds", it.durationSeconds).put("instruction", it.instruction)) } })
        prefs.edit().putString("snapshot", json.toString()).apply()
    }
    private fun restore() {
        val raw = prefs.getString("snapshot", null) ?: return
        try {
            val j = JSONObject(raw)
            val intervals = j.getJSONArray("intervals").let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let { SessionInterval(it.getString("activity"), it.getInt("seconds"), it.getString("instruction")) } } }
            val counts = j.getJSONArray("counts").let { array -> (0 until array.length()).map { array.getInt(it) }.toSet() }
            val saved = SessionSnapshot(j.getLong("workoutId"), j.getString("name"), intervals, j.getInt("index"), j.getLong("remainingMs"), j.getLong("targetMs"), j.getBoolean("paused"), j.getBoolean("complete"), j.getBoolean("tenSpoken"), counts)
            if (!saved.complete) {
                if (!startedForeground) foreground(notification(saved))
                apply(engine.restore(saved))
                startLoop()
            } else { app.session.value = saved; stopSelf() }
        } catch (_: Exception) { prefs.edit().remove("snapshot").apply() }
    }
    private fun stopSession() {
        loop?.cancel()
        engine.stop()
        speech.onIdle = null
        speech.interrupt()
        app.session.value = null
        prefs.edit().remove("snapshot").apply()
        if (power.isHeld) power.release()
        if (startedForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        startedForeground = false
        stopSelf()
    }
    override fun onDestroy() {
        loop?.cancel(); scope.cancel(); speech.close()
        if (power.isHeld) power.release()
        super.onDestroy()
    }
}

fun formatTime(ms: Long): String {
    val seconds = ceil(ms.coerceAtLeast(0) / 1000.0).toInt()
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
