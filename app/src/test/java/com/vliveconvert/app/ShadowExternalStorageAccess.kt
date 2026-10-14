package com.vliveconvert.app

import android.os.Environment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment

/**
 * 补 Robolectric 缺失的 `Environment.isExternalStorageManager()`。
 *
 * 已用探针证实：真调该方法在 Robolectric 下抛 `ArrayIndexOutOfBoundsException`
 * （`android.os.Environment.isExternalStorageManager` 未被 shadow 覆盖）。
 * 后果是「修复文件时间」这类以该权限为门禁的入口在测试里根本走不到，
 * 也就测不到「授权后能不能真的进页」。
 *
 * 用法：`@Config(shadows = [ShadowExternalStorageAccess::class])`，用例里设
 * [granted] 为 true / false 分别覆盖两条分支。
 */
@Implements(Environment::class)
class ShadowExternalStorageAccess : ShadowEnvironment() {

    companion object {
        /** 由用例设定「所有文件访问权限」是否已授予；默认未授予 */
        @JvmStatic
        var granted: Boolean = false

        @JvmStatic
        @Implementation
        fun isExternalStorageManager(): Boolean = granted
    }
}
