package de.lychtlos.fundustv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Scrollt nur so weit, dass das fokussierte Element sichtbar ist – mit Rand (12 % der Höhe),
 * damit Titel unter dem Cover und die Vergrößerung beim Fokus nicht abgeschnitten werden.
 */
@OptIn(ExperimentalFoundationApi::class)
val MinimalScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val m = containerSize * 0.12f
        val start = offset - m
        val end = offset + size + m
        return when {
            start >= 0 && end <= containerSize -> 0f
            size + 2 * m > containerSize -> offset - m          // größer als der Bereich: Anfang zeigen
            start < 0 -> start
            else -> end - containerSize
        }
    }
}
