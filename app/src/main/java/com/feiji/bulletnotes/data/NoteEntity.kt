package com.feiji.bulletnotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 飞记笔记实体类
 * @property id 唯一主键
 * @property content 笔记正文内容
 * @property dateString 关联日期字符串 (格式: yyyy-MM-dd)
 * @property bulletType 标识符状态 (SQUARE: 方框, CHECKED: 对勾, BULLET_DOT: 实心圆点)
 * @property orderIndex 排序索引（用于拖拽调整先后顺序）
 * @property isDeleted 是否被移入回收站 (软删除)
 * @property createdAt 创建时间毫秒值
 * @property updatedAt 更新时间毫秒值
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String = "",
    val dateString: String,
    val bulletType: BulletType = BulletType.SQUARE,
    val orderIndex: Int = 0,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
