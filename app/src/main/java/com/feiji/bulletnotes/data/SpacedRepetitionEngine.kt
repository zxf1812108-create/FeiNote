package com.feiji.bulletnotes.data

import android.content.Context
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
 * 用户可自定义的记忆曲线参数配置。
 */
data class MemoryCurveConfig(
    val unfamiliarDays: Int = SpacedRepetitionEngine.DEFAULT_UNFAMILIAR_DAYS,
    val initialSomewhatDays: Int = SpacedRepetitionEngine.DEFAULT_INITIAL_SOMEWHAT_DAYS,
    val initialVeryDays: Int = SpacedRepetitionEngine.DEFAULT_INITIAL_VERY_DAYS,
    val initialEaseFactor: Float = SpacedRepetitionEngine.DEFAULT_EASE_FACTOR,
    val somewhatMultiplier: Float = SpacedRepetitionEngine.DEFAULT_SOMEWHAT_MULTIPLIER,
    val maxIntervalDays: Int = SpacedRepetitionEngine.MAX_INTERVAL_DAYS
) {
    fun normalized(): MemoryCurveConfig {
        val safeMax = maxIntervalDays.coerceIn(
            SpacedRepetitionEngine.MIN_MAX_INTERVAL_DAYS,
            SpacedRepetitionEngine.HARD_MAX_INTERVAL_DAYS
        )
        val safeUnfamiliar = unfamiliarDays.coerceIn(1, minOf(30, safeMax))
        val safeSomewhat = initialSomewhatDays.coerceIn(1, minOf(60, safeMax))
        val safeVery = initialVeryDays.coerceIn(1, minOf(90, safeMax))
        val safeEase = SpacedRepetitionEngine.normalizeEaseFactor(initialEaseFactor)
        val safeMultiplier = ((somewhatMultiplier.coerceIn(0.30f, 1.00f)) * 100f).roundToInt() / 100f
        return MemoryCurveConfig(
            unfamiliarDays = safeUnfamiliar,
            initialSomewhatDays = safeSomewhat,
            initialVeryDays = safeVery,
            initialEaseFactor = safeEase,
            somewhatMultiplier = safeMultiplier,
            maxIntervalDays = safeMax
        )
    }
}

/**
 * 持久化存储用户自定义的记忆曲线参数。
 */
object MemoryCurvePreferences {
    private const val PREFS_NAME = "feiji_memory_curve_prefs"
    private const val KEY_UNFAMILIAR_DAYS = "unfamiliar_days"
    private const val KEY_INITIAL_SOMEWHAT_DAYS = "initial_somewhat_days"
    private const val KEY_INITIAL_VERY_DAYS = "initial_very_days"
    private const val KEY_INITIAL_EASE_FACTOR = "initial_ease_factor"
    private const val KEY_SOMEWHAT_MULTIPLIER = "somewhat_multiplier"
    private const val KEY_MAX_INTERVAL_DAYS = "max_interval_days"

    fun load(context: Context): MemoryCurveConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = MemoryCurveConfig()
        return MemoryCurveConfig(
            unfamiliarDays = prefs.getInt(KEY_UNFAMILIAR_DAYS, defaults.unfamiliarDays),
            initialSomewhatDays = prefs.getInt(KEY_INITIAL_SOMEWHAT_DAYS, defaults.initialSomewhatDays),
            initialVeryDays = prefs.getInt(KEY_INITIAL_VERY_DAYS, defaults.initialVeryDays),
            initialEaseFactor = prefs.getFloat(KEY_INITIAL_EASE_FACTOR, defaults.initialEaseFactor),
            somewhatMultiplier = prefs.getFloat(KEY_SOMEWHAT_MULTIPLIER, defaults.somewhatMultiplier),
            maxIntervalDays = prefs.getInt(KEY_MAX_INTERVAL_DAYS, defaults.maxIntervalDays)
        ).normalized()
    }

    fun save(context: Context, config: MemoryCurveConfig): MemoryCurveConfig {
        val safe = config.normalized()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_UNFAMILIAR_DAYS, safe.unfamiliarDays)
            .putInt(KEY_INITIAL_SOMEWHAT_DAYS, safe.initialSomewhatDays)
            .putInt(KEY_INITIAL_VERY_DAYS, safe.initialVeryDays)
            .putFloat(KEY_INITIAL_EASE_FACTOR, safe.initialEaseFactor)
            .putFloat(KEY_SOMEWHAT_MULTIPLIER, safe.somewhatMultiplier)
            .putInt(KEY_MAX_INTERVAL_DAYS, safe.maxIntervalDays)
            .apply()
        return safe
    }
}

/**
 * 轻量改良版 SM-2 记忆曲线排期引擎：
 * - 基于历史连续记住次数、当前间隔天数与熟练倍率动态推演下次复习间隔；
 * - 支持用户自定义初始间隔、熟练倍率、大概了解系数与最大间隔上限；
 * - 统一以实际复习当天（today）为起点向后排期。
 */
object SpacedRepetitionEngine {
    const val DEFAULT_UNFAMILIAR_DAYS: Int = 1
    const val DEFAULT_INITIAL_SOMEWHAT_DAYS: Int = 2
    const val DEFAULT_INITIAL_VERY_DAYS: Int = 4
    const val DEFAULT_SOMEWHAT_MULTIPLIER: Float = 0.75f

    const val DEFAULT_EASE_FACTOR: Float = 2.5f
    const val MIN_EASE_FACTOR: Float = 1.3f
    const val MAX_EASE_FACTOR: Float = 3.5f
    const val MIN_MAX_INTERVAL_DAYS: Int = 7
    const val MAX_INTERVAL_DAYS: Int = 180
    const val HARD_MAX_INTERVAL_DAYS: Int = 3650

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
        val safeInterval = note.reviewIntervalDays.coerceIn(0, HARD_MAX_INTERVAL_DAYS)
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
        today: LocalDate = LocalDate.now(),
        config: MemoryCurveConfig = MemoryCurveConfig()
    ): ReviewScheduleResult {
        val cfg = config.normalized()
        val maxInterval = cfg.maxIntervalDays
        val currentCount = note.reviewCount.coerceAtLeast(0)
        val currentInterval = note.reviewIntervalDays.coerceIn(0, maxInterval)
        val isInitialStage = currentCount <= 0 || currentInterval <= 0
        val currentEase = if (isInitialStage && note.lastReviewedAt == null) {
            cfg.initialEaseFactor
        } else {
            normalizeEaseFactor(note.easeFactor)
        }

        val somewhatInterval = if (isInitialStage) {
            cfg.initialSomewhatDays.coerceAtMost(maxInterval)
        } else {
            maxOf(
                currentInterval + 1,
                (currentInterval * currentEase * cfg.somewhatMultiplier).roundToInt()
            ).coerceAtMost(maxInterval)
        }

        val veryInterval = if (isInitialStage) {
            cfg.initialVeryDays.coerceAtMost(maxInterval)
        } else {
            val base = maxOf(
                currentInterval + 2,
                (currentInterval * currentEase).roundToInt()
            )
            val ordered = if (somewhatInterval < maxInterval) {
                maxOf(base, somewhatInterval + 1)
            } else {
                base
            }
            ordered.coerceAtMost(maxInterval)
        }

        return when (familiarity) {
            ReviewFamiliarity.UNFAMILIAR -> {
                val nextEase = roundEaseFactor((currentEase - 0.20f).coerceAtLeast(MIN_EASE_FACTOR))
                val days = cfg.unfamiliarDays.coerceAtMost(maxInterval)
                ReviewScheduleResult(
                    nextReviewCount = 0,
                    nextIntervalDays = days,
                    nextEaseFactor = nextEase,
                    nextDate = today.plusDays(days.toLong())
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

    /**
     * 模拟新卡片在连续选择某一熟悉度时前若干次的复习间隔天数，用于在参数设置弹窗中实时预览曲线节奏。
     */
    fun simulateIntervals(
        config: MemoryCurveConfig,
        familiarity: ReviewFamiliarity,
        steps: Int = 4
    ): List<Int> {
        val safeConfig = config.normalized()
        var simulatedNote = NoteEntity(
            dateString = "2026-01-01",
            bulletType = BulletType.STAR,
            content = "",
            reviewCount = 0,
            reviewIntervalDays = 0,
            easeFactor = safeConfig.initialEaseFactor,
            lastReviewedAt = null
        )
        var cursorDate = LocalDate.of(2026, 1, 1)
        return buildList(steps) {
            repeat(steps) {
                val result = computeNextReview(simulatedNote, familiarity, cursorDate, safeConfig)
                add(result.nextIntervalDays)
                cursorDate = result.nextDate
                simulatedNote = simulatedNote.copy(
                    reviewCount = result.nextReviewCount,
                    reviewIntervalDays = result.nextIntervalDays,
                    easeFactor = result.nextEaseFactor,
                    lastReviewedAt = 1L
                )
            }
        }
    }
}
