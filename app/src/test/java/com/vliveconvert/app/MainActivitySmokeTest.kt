package com.vliveconvert.app

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * UI / Activity 层冒烟测试（Robolectric：在 JVM 上跑真实 Android 框架）。
 *
 * 为什么需要这一层：既有单测只覆盖 `core/` 的字节级转换管道，**不实例化 Activity、
 * 不构建 Compose 树**——v1.3.1「点击开始转换闪退」以及「空状态中央的 + 点不动」
 * 这类问题都出在这一层，单测全绿也发现不了。
 *
 * 说明：Robolectric **不会**强制执行清单权限（`startForeground` 不会抛
 * SecurityException），所以前台服务权限缺失那类问题由 [ManifestContractTest] 兜底，
 * 两者互补。
 *
 * 用 @Config(sdk = [34]) 锁定到 minSdk：Robolectric 的 android-all 运行时
 * 对最新 SDK 的跟进通常滞后，锁 minSdk 最稳且仍能覆盖目标机型行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySmokeTest {

    private fun launchActivity() =
        Robolectric.buildActivity(MainActivity::class.java).setup()

    /** 未授予媒体权限时应能正常启动并停在权限引导页（不闪退、不空指针） */
    @Test
    fun launchesWithoutCrashWhenPermissionsMissing() {
        val controller = launchActivity()
        val activity = controller.get()
        assertNotNull("Activity 应成功创建", activity)
        assertFalse("未授权时不应渲染主页", activity.isFinishing)
        controller.pause().stop().destroy()
    }

    /** 授予媒体权限后应能渲染主界面（Compose 树完整构建，含空状态） */
    @Test
    fun rendersMainScreenWhenMediaPermissionGranted() {
        val app = ApplicationProvider.getApplicationContext<VliveApp>()
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        val controller = launchActivity()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    /**
     * 授予「所有文件访问权限」后，设置页与关于页也应能正常构建。
     * 关于页会读取内置的 GPL-3.0 全文（res/raw），此处顺带验证该资源可正常读取——
     * 若资源名写错或打包遗漏，会在打开页面时崩溃。
     */
    @Test
    fun aboutScreenLicenseResourceIsReadable() {
        val app = ApplicationProvider.getApplicationContext<VliveApp>()
        val text = app.resources.openRawResource(R.raw.license_gpl3)
            .bufferedReader().use { it.readText() }
        assertTrue("内置许可证文本应非空", text.length > 10_000)
        assertTrue("应为 GPL-3.0 全文", text.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue("应包含第 3 版声明", text.contains("Version 3"))
    }

    /** 应用内品牌名资源存在且与桌面名称区分（品牌统一后各处都取自该资源） */
    @Test
    fun appDisplayNameResourceMatchesTopBarText() {
        val app = ApplicationProvider.getApplicationContext<VliveApp>()
        val displayName = app.getString(R.string.app_display_name)
        assertTrue("应用内品牌名应为 Vivo Live Photo", displayName == "Vivo Live Photo")
        assertTrue(
            "桌面图标名应保留「yuting改进版」以便与原版区分",
            app.getString(R.string.app_name).contains("yuting")
        )
    }
}
