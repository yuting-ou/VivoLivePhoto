package com.vliveconvert.app

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回收站确认弹窗悬着时 Activity 重建（旋转/主题切换/后台回收），
 * 删除上下文（deleteBaseStatus / pendingDeleteCount / lastTrashUris）必须跨实例幸存。
 *
 * 修的缺陷：这些字段原本是纯实例字段——系统弹窗在上层时重建，结果回调落在**新实例**上，
 * lastTrashUris 为空 → 恢复记录不入库，「恢复原图」入口对这批删除永久失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrashStateRecreationTest {

    @Suppress("UNCHECKED_CAST")
    private fun get(act: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(act)

    private fun set(act: MainActivity, name: String, value: Any?) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(act, value)

    @Test
    fun deleteContextSurvivesActivityRecreation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { act ->
            // 模拟「删除请求已拉起、系统确认弹窗悬着」的字段状态
            set(act, "deleteBaseStatus", "转换完成：成功 2 个，失败 0 个")
            set(act, "pendingDeleteCount", 2)
            set(act, "lastTrashUris", listOf("content://media/1", "content://media/2"))
        }
        scenario.recreate()
        scenario.onActivity { act ->
            assertEquals("基础状态文案必须跨重建",
                "转换完成：成功 2 个，失败 0 个", get(act, "deleteBaseStatus"))
            assertEquals("待删除计数必须跨重建", 2, get(act, "pendingDeleteCount"))
            assertEquals(
                "被回收站的原图 URI 必须跨重建（否则恢复记录不入库）",
                listOf("content://media/1", "content://media/2"), get(act, "lastTrashUris"))
        }
    }
}
