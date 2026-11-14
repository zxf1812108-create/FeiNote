package com.feiji.bulletnotes.ui.theme

import androidx.compose.ui.graphics.Color

// 浅色/深色模式严格使用需求指定的前景色与背景色。
val LightContent = Color(0xFF333333)
val LightBackground = Color(0xFFF5F5F5)
val DarkContent = Color(0xFFD0D0D0)
val DarkBackground = Color(0xFF1A1A1A)

// 兼容界面中原有颜色名称，确保辅助文字、边框和图标也遵循当前主题。
val InkBlack = LightContent
val CharcoalGray = LightContent
val SlateMuted = LightContent
val LineBorder = LightContent
val PaperBackground = LightBackground
val CardBackground = LightBackground
val AccentPrimary = LightContent
val AccentSuccess = LightContent
val AccentDanger = LightContent
val AccentWarning = LightContent
val DarkPaperBackground = DarkBackground
val DarkCardBackground = DarkBackground
val DarkTextPrimary = DarkContent
val DarkTextSecondary = DarkContent

