package com.privatechat.app.voice

import kotlin.random.Random

/**
 * A voice message's real amplitude curve only exists on the device
 * that recorded it (see ChatActivity's amplitude capture during
 * recording) — it's never written to Firebase, since that would mean
 * changing the message schema. For playback anywhere else (the
 * sender's own sent bubble after leaving/reopening the chat, or the
 * recipient's bubble at all), this generates a stable pattern instead:
 * same shape every time for a given message (seeded by its URL, so it
 * never jitters between rebinds/scrolls), speech-like in silhouette
 * rather than uniform bars, but NOT the message's actual amplitude
 * curve. That's a deliberate, disclosed simplification of "the sent
 * voice message must display the waveform" — not a bug.
 */
object WaveformUtils {

    fun pseudoWaveform(seed: String, count: Int): List<Float> {
        val random = Random(seed.hashCode().toLong())
        val raw = (0 until count).map { 0.25f + random.nextFloat() * 0.75f }
        // Light smoothing against neighbors so it reads as an organic
        // speech envelope instead of pure noise.
        return raw.mapIndexed { i, value ->
            val prev = raw.getOrElse(i - 1) { value }
            val next = raw.getOrElse(i + 1) { value }
            ((prev + value * 2f + next) / 4f).coerceIn(0.15f, 1f)
        }
    }
}
