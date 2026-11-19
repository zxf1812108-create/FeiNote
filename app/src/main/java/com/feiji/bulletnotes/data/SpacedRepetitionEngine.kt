package com.feiji.bulletnotes.data

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 五角星知识卡片的复习熟悉度档位。
 */
enum class ReviewFamiliarity(val label: String) {
    UNFAMILIAR("陌生"),
    SOMEWHAT_FAMILIAR("大概了解"),
    VERY_FAMILIAR("很熟悉")
}

/**
 * 单次复习排期结果。
 */
data class ReviewScheduleResult(
    val nextReviewCount: Int,
    val nextIntervalDays: Int,
    val nextEaseFactor: Float,
    val nextDate: LocalDate
)

/**
 * 轻量改良版 SM-2 记忆曲线排期引擎：
 * - 基于历史连续记住次数、当前间隔天数与熟练倍率动态推演下次复习间隔；
 * - 统一以实际复习当天（today）为起点向后排期；
 * - 最大复习间隔封顶为 180 天，且在未触顶前严格保持 陌生 < 大概了解 < 很熟悉。
 */
object SpacedRepetitionEngine {
    const val DEFAULT_EASE_FACTOR: Float = 2.5f
    const val MIN_EASE_FACTOR: Float = 1.3f
    const val MAX_EASE_FACTOR: Float = 3.5f
    const val MAX_INTERVAL_DAYS: Int = 180

    fun normalizeEaseFactor(raw: Float): Float {
        if (!raw.isFinite() || raw <= 0f) return DEFAULT_EASE_FACTOR
        return roundEaseFactor(raw.coerceIn(MIN_EASE_FACTOR, MAX_EASE_FACTOR))
    }

    private fun roundEaseFactor(value: Float): Float =
        (value * 100f).roundToInt() / 100f

    /**
     * 规范化从旧版备份或远端同步反序列化出的复习字段，避免缺失字段被 Gson 填成 0 导致算法异常。
     */
    fun sanitizeNoteReviewFields(note: NoteEntity): NoteEntity {
        val safeCount = note.reviewCount.coerceAtLeast(0)
        val safeInterval = note.reviewIntervalDays.coerceIn(0, MAX_INTERVAL_DAYS)
        val safeEase = normalizeEaseFactor(note.easeFactor)
        val safeLastReviewed = note.lastReviewedAt?.takeIf { it > 0L }
        return if (
            safeCount == note.reviewCount &&
            safeInterval == note.reviewIntervalDays &&
            safeEase == note.easeFactor &&
            safeLastReviewed == note.lastReviewedAt
        ) {
            note
        } else {
            note.copy(
                reviewCount = safeCount,
                reviewIntervalDays = safeInterval,
                easeFactor = safeEase,
                lastReviewedAt = safeLastReviewed
            )
        }
    }

    fun computeNextReview(
        note: NoteEntity,
        familiarity: ReviewFamiliarity,
        today: LocalDate = LocalDate.now()
    ): ReviewScheduleResult {
        val currentCount = note.reviewCount.coerceAtLeast(0)
        val currentInterval = note.reviewIntervalDays.coerceIn(0, MAX_INTERVAL_DAYS)
        val currentEase = normalizeEaseFactor(note.easeFactor)

        val isInitialStage = currentCount <= 0 || currentInterval <= 0

        val somewhatInterval = if (isInitialStage) {
            2
        } else {
            maxOf(
                currentInterval + 1,
                (currentInterval * currentEase * 0.75f).roundToInt()
            ).coerceAtMost(MAX_INTERVAL_DAYS)
        }

        val veryInterval = if (isInitialStage) {
            4
        } else {
            val base = maxOf(
                currentInterval + 2,
                (currentInterval * currentEase).roundToInt()
            )
            val ordered = if (somewhatInterval < MAX_INTERVAL_DAYS) {
                maxOf(base, somewhatInterval + 1)
            } else {
                base
            }
            ordered.coerceAtMost(MAX_INTERVAL_DAYS)
        }

        return when (familiarity) {
            ReviewFamiliarity.UNFAMILIAR -> {
                val nextEase = roundEaseFactor((currentEase - 0.20f).coerceAtLeast(MIN_EASE_FACTOR))
                ReviewScheduleResult(
                    nextReviewCount = 0,
                    nextIntervalDays = 1,
                    nextEaseFactor = nextEase,
                    nextDate = today.plusDays(1L)
                )
            }

            ReviewFamiliarity.SOMEWHAT_FAMILIAR -> {
                val nextEase = roundEaseFactor((currentEase - 0.05f).coerceAtLeast(MIN_EASE_FACTOR))
                ReviewScheduleResult(
                    nextReviewCount = currentCount + 1,
                    nextIntervalDays = somewhatInterval,
                    nextEaseFactor = nextEase,
                    nextDate = today.plusDays(somewhatInterval.toLong())
                )
            }

            ReviewFamiliarity.VERY_FAMILIAR -> {
                val nextEase = roundEaseFactor((currentEase + 0.15f).coerceAtMost(MAX_EASE_FACTOR))
                ReviewScheduleResult(
                    nextReviewCount = currentCount + 1,
                    nextIntervalDays = veryInterval,
                    nextEaseFactor = nextEase,
                    nextDate = today.plusDays(veryInterval.toLong())
                )
            }
        }
    }
}
