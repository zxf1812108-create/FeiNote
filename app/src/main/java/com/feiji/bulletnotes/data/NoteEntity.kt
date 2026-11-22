package com.feiji.bulletnotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 飞记笔记实体类
 * @property id 本地唯一主键
 * @property content 笔记正文内容
 * @property dateString 关联日期字符串 (格式: yyyy-MM-dd)
 * @property bulletType 标识符状态 (SQUARE: 方框, CHECKED: 对勾, BULLET_DOT: 实心圆点, STAR: 五角星)
 * @property orderIndex 排序索引（用于拖拽调整先后顺序）
 * @property isDeleted 是否被移入回收站 (软删除)
 * @property deletedAt 移入回收站时间戳
 * @property createdAt 创建时间毫秒值
 * @property updatedAt 更新时间毫秒值
 * @property syncId 跨设备同步全局唯一标识 (UUID)
 * @property reviewCount 五角星知识卡片连续记住次数（用于记忆曲线推演）
 * @property reviewIntervalDays 当前记忆曲线间隔天数
 * @property easeFactor 记忆熟练倍率（默认 2.5，范围 1.3~3.5）
 * @property lastReviewedAt 最近一次复习时间戳毫秒值
 * @property alarmTimeMillis 笔记闹钟触发时间戳毫秒值（null 表示未设置闹钟）
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
    val updatedAt: Long = System.currentTimeMillis(),
    val syncId: String = UUID.randomUUID().toString(),
    val reviewCount: Int = 0,
    val reviewIntervalDays: Int = 0,
    val easeFactor: Float = SpacedRepetitionEngine.DEFAULT_EASE_FACTOR,
    val lastReviewedAt: Long? = null,
    val alarmTimeMillis: Long? = null
)

/**
 * 永久删除墓碑记录：用于在多设备同步时传播彻底删除操作，避免已删笔记在另一台设备上复活。
 */
@Entity(tableName = "deleted_sync_tombstones")
data class DeletedNoteTombstone(
    @PrimaryKey
    val syncId: String,
    val deletedAt: Long
)
