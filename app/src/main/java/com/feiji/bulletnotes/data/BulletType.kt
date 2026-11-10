package com.feiji.bulletnotes.data

/**
 * 笔记标识符类型：
 * SQUARE: 方框（待办/任务）
 * CHECKED: 对勾（已完成）
 * BULLET_DOT: 实心圆点（普通便签/想法）
 */
enum class BulletType {
    SQUARE,
    CHECKED,
    BULLET_DOT;

    fun next(): BulletType {
        return when (this) {
            SQUARE -> CHECKED
            CHECKED -> BULLET_DOT
            BULLET_DOT -> SQUARE
        }
    }
}
