package com.intervalcoach.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.ArrayDeque
import java.util.Locale

class SpeechCoach(context: Context) : TextToSpeech.OnInitListener {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener { }.build()
    private val queue = ArrayDeque<String>()
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var speaking = false
    private var generation = 0
    private var focusHeld = false
    @Volatile private var closed = false
    @Volatile var onIdle: (() -> Unit)? = null

    override fun onInit(status: Int) {
        if (closed || status != TextToSpeech.SUCCESS) { onIdle?.invoke(); return }
        tts.language = Locale.US
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?) = finished(utteranceId)
        })
        synchronized(this) { ready = true; pump() }
    }
    @Synchronized fun say(text: String) { if (!closed) { queue.addLast(text); pump() } }
    @Synchronized fun interrupt() {
        generation++
        queue.clear()
        speaking = false
        if (ready) tts.stop()
        abandonFocus()
    }
    private fun finished(id: String?) {
        synchronized(this) {
            if (id != generation.toString() || closed) return
            speaking = false
            abandonFocus()
            pump()
        }
    }
    private fun pump() {
        if (!ready || closed || speaking) return
        if (queue.isEmpty()) { onIdle?.invoke(); return }
        val text = queue.removeFirst()
        generation++
        focusHeld = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        speaking = true
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), generation.toString())
        if (result == TextToSpeech.ERROR) { speaking = false; abandonFocus(); pump() }
    }
    private fun abandonFocus() { if (focusHeld) { audio.abandonAudioFocusRequest(focus); focusHeld = false } }
    @Synchronized fun close() { closed = true; interrupt(); tts.shutdown() }
}
