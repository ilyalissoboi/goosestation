// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/** An unlock card's kind: an achievement, or the whole set mastered. */
enum class CardKind { Unlock, Mastery }

/** One of the core's achievement notices, as the game screen shows it. */
sealed interface AchievementNotice {
    /**
     * A card at the top centre: its [title], [text], picture ([imagePath], may be empty), an unlock's [points], a
     * [note] under the text (an unlock earned without a connection), and how long it shows ([durationMs]).
     */
    data class Card(
        val kind: CardKind,
        val title: String,
        val text: String,
        val imagePath: String,
        val points: Int?,
        val note: String? = null,
        val durationMs: Long =
            if (kind ==
                CardKind.Mastery
            ) {
                AchievementOverlay.MASTERY_MS
            } else {
                AchievementOverlay.UNLOCK_MS
            },
    ) : AchievementNotice

    /** A line in the message stack, with its picture ([imagePath], or null for none). */
    data class Message(
        val text: String,
        val imagePath: String?,
    ) : AchievementNotice
}

/** The core's achievement notices, sorted by their keys. */
object AchievementNotices {
    /**
     * The notice for the core's notification [key], with its [title], [text], picture and an unlock's [points] (-1); an
     * unlock [offline] (with no connection) says it's sent later. An unlock's card shows for the core's [seconds]
     * (`[Cheevos] NotificationsDuration`); the mastery card keeps its own time.
     */
    fun from(
        key: String,
        title: String,
        text: String,
        imagePath: String,
        points: Int,
        offline: Boolean = false,
        seconds: Float = AchievementOverlay.UNLOCK_MS / 1000f,
    ): AchievementNotice =
        when {
            key.startsWith("achievement_unlock_") ->
                AchievementNotice.Card(
                    CardKind.Unlock,
                    title,
                    text,
                    imagePath,
                    points.takeIf { it >= 0 },
                    AchievementQueueWords.CARD_NOTE.takeIf { offline },
                    (seconds * 1000).toLong(),
                )
            key == "achievement_mastery" -> AchievementNotice.Card(CardKind.Mastery, title, text, imagePath, null)
            key == "achievement_summary" -> AchievementNotice.Message(text, imagePath.ifEmpty { null })
            key.startsWith("leaderboard_") -> AchievementNotice.Message("$title: $text", imagePath.ifEmpty { null })
            else -> AchievementNotice.Message(listOf(title, text).filter { it.isNotEmpty() }.joinToString(": "), null)
        }

    /** A toast's line: its [title], or its [message] when it has none. */
    fun toast(
        title: String,
        message: String,
    ) = title.ifEmpty { message }
}
