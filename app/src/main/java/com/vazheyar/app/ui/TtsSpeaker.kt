package com.vazheyar.app.ui

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

class TtsSpeaker(private val tts: TextToSpeech) {
    fun speak(text: String) {
        tts.language = Locale.US
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word-${text.hashCode()}")
    }
    fun close() {
        tts.stop()
        tts.shutdown()
    }
}

@Composable
fun rememberTtsSpeaker(): TtsSpeaker {
    val context = LocalContext.current
    val speaker = remember { TtsSpeaker(TextToSpeech(context) {}) }
    DisposableEffect(Unit) {
        onDispose { speaker.close() }
    }
    return speaker
}
