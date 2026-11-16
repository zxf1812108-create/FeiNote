package com.feiji.bulletnotes.data

/**
 * 笔记标识符类型：
 * SQUARE: 方框（待办/任务）
 * CHECKED: 已完成标识（兼容原有数据）
 * BULLET_DOT: 实心圆点（普通便签/想法）
 * STAR: 五角星（多段笔记）
 */
enum class BulletType {
    SQUARE,
    CHECKED,
    BULLET_DOT,
    STAR;

    fun next(): BulletType {
        return when (this) {
            SQUARE -> CHECKED
            CHECKED -> BULLET_DOT
            BULLET_DOT -> SQUARE
            STAR -> BULLET_DOT
        }
    }
}
