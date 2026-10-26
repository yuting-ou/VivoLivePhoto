package com.vliveconvert.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 减少动效：省电 / 低性能设备 / 无障碍偏好。
 *
 * `reduceMotion` 是用户显式偏好（设置页开关，持久化 reduce_motion）；
 * `systemAnimatorOff` 是系统「移除动画」（animator_duration_scale=0，由
 * MainActivity 在 onCreate/onResume 刷新）——系统已经不要动画时，应用内
 * 剩余的动画帧也不该白白烧电。[effective] 是所有动画分支的唯一判据。
 */
object MotionPrefs {

    var reduceMotion by mutableStateOf(false)
        internal set
    var systemAnimatorOff by mutableStateOf(false)
        internal set

    val effective: Boolean get() = reduceMotion || systemAnimatorOff

    /** 进程启动 / 设置页加载时调用（读 SharedPreferences + 系统动画缩放） */
    fun load(context: Context) {
        reduceMotion = try {
            context.getSharedPreferences("vliveconvert", Context.MODE_PRIVATE)
                .getBoolean("reduce_motion", false)
        } catch (_: Exception) {
            false
        }
        refreshSystem(context)
    }

    /** 系统动画缩放可能随时被改（无障碍/开发者选项），onResume 里刷新 */
    fun refreshSystem(context: Context) {
        systemAnimatorOff = try {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }

    /** 设置页开关（写偏好 + 立即生效） */
    fun set(context: Context, value: Boolean) {
        reduceMotion = value
        try {
            context.getSharedPreferences("vliveconvert", Context.MODE_PRIVATE)
                .edit().putBoolean("reduce_motion", value).apply()
        } catch (_: Exception) {
        }
    }
}

/** 动画分支的唯一判据：true 时所有循环/进出场动画走瞬时静态分支 */
val LocalReduceMotion = compositionLocalOf { false }
