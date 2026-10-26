package com.vliveconvert.app

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.ConvertCenter
import com.vliveconvert.app.service.ConvertService
import com.vliveconvert.app.ui.ConvertItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * 「点击开始转换」全链路 UI 测试（Robolectric 真实 Android 框架）。
 *
 * 动因：v1.7.8 用户真机反馈「点击开始转换无反应」。既有测试只覆盖
 * 冒烟（Activity 能创建）与 core 管道，**没有一条用例真正按下过主路径按钮**——
 * 换包名这类看似与转换无关的改动，同样可能在这条链上断掉。
 * 本类把「点击 → 门禁 → 预检弹窗 → 启动服务 → 批次跑完」逐段断言，
 * 任何一段断链都会在此显形。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvertFlowUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()

    @Before
    fun resetState() {
        // ConvertCenter 是进程级单例：测试间必须复位，否则用例互相污染
        compose.activity.runOnUiThread {
            ConvertCenter.items.clear()
            ConvertCenter.isConverting = false
            ConvertCenter.progress = 0f
            ConvertCenter.progressDetail = ""
            ConvertCenter.statusText = ""
            ConvertCenter.pendingTrashUris.clear()
            ConvertCenter.pendingTrashCount = 0
        }
        File(compose.activity.filesDir, "convert_queue.json").delete()
    }

    /** 授予全部相关权限（含位置——缺它会先弹位置门禁，走不到转换）。
     *  Compose rule 会先启动 Activity，因此授权后必须 recreate 让界面重建到主屏 */
    private fun grantAllPermissions() {
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    /** 物理存在的双文件样本：jpg + 伴生 mp4（转换执行与否不影响 UI 链路断言） */
    private fun addPendingItem(name: String): MediaItem {
        val dir = File(compose.activity.filesDir, "samples").apply { mkdirs() }
        val jpg = File(dir, "$name.jpg")
        val mp4 = File(dir, "$name.mp4")
        if (!jpg.exists()) {
            jpg.writeBytes(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte()))
            mp4.writeBytes(ByteArray(16))
        }
        val mi = MediaItem(
            id = name.hashCode().toLong(),
            path = jpg.absolutePath,
            name = "$name.jpg",
            bucketId = 1L,
            dateTaken = 1_754_000_000_000L,
            dateModified = 1_754_000_000L,
            size = jpg.length()
        )
        compose.activity.runOnUiThread {
            ConvertCenter.items.add(ConvertItem(item = mi))
        }
        return mi
    }

    /** 反射读 Activity 内部状态：`by mutableStateOf` 委托字段带 $delegate 后缀，
     *  普通字段就是原名（弹窗在 Robolectric 下是独立 window，Compose 测试查不到
     *  其语义节点，状态断言是可靠的替代） */
    @Suppress("SameParameterValue")
    private fun internalState(name: String): Any? {
        val cls = MainActivity::class.java
        val f = try {
            cls.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            cls.getDeclaredField(name).apply { isAccessible = true }
        }
        val v = f.get(compose.activity)
        return if (v is androidx.compose.runtime.State<*>) v.value else v
    }

    // ---------- 1) 主按钮点击必有反应：预检状态置上（弹窗渲染交给系统） ----------

    @Test
    fun clickStart_showsPreflightDialog() {
        grantAllPermissions()
        addPendingItem("IMG_A")
        compose.waitForIdle()

        compose.onNodeWithText("开始转换（1）").performClick()
        compose.waitForIdle()

        assertEquals(
            "点击后应置上预检弹窗状态（位置权限已授，不应被门禁拦截）",
            true, internalState("showPreflightDialog"))
        val info = internalState("preflight")
        assertTrue("预检信息应已计算", info != null)
        // 玻璃弹窗是应用内浮层，不再是独立系统 window：内容必须同窗可读
        compose.onNodeWithText("源文件体积").assertExists()
        // 时间线副作用披露：产物是新媒体行，date_added=转换当天，
        // 微信等按加入时间分组的应用会把它们归到「今天」——必须在操作点说清
        compose.onNodeWithText("加入相册时间").assertExists()
        compose.onNodeWithText("以后不再提示").assertExists()
    }

    // ---------- 2) 无位置权限：点击后必弹位置门禁（同样不许「无反应」） ----------

    @Test
    fun clickStart_withoutLocationPermission_showsGateDialog() {
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        addPendingItem("IMG_B")
        compose.waitForIdle()

        compose.onNodeWithText("开始转换（1）").performClick()
        compose.waitForIdle()

        // 门禁拦截：状态置上 + 主界面出现「去授权」警示条（主窗口节点，可直接查）
        assertEquals(
            "缺位置权限时应弹位置门禁（showLocationDialog）",
            true, internalState("showLocationDialog"))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("去授权").fetchSemanticsNodes().isNotEmpty()
        }
        // 不许静默：不得提前进入转换态
        assertTrue("门禁拦截时不应开始转换", !ConvertCenter.isConverting)
    }

    // ---------- 3) 预检确认 → 进入转换态 + 前台服务已启动 ----------

    @Test
    fun confirmPreflight_startsForegroundService() {
        grantAllPermissions()
        addPendingItem("IMG_C")
        compose.waitForIdle()

        compose.onNodeWithText("开始转换（1）").performClick()
        compose.waitForIdle()
        assertEquals(true, internalState("showPreflightDialog"))

        // 执行弹窗确认按钮的同一动作（按钮 onClick = 关弹窗 + ConvertCenter.start）；
        // 弹窗按钮本身在独立 window，Robolectric 下语义查询不可靠，动作级断言等价
        ConvertCenter.start(compose.activity)

        assertTrue("确认后应进入转换态", ConvertCenter.isConverting)
        val started: Intent? = shadowOf(compose.activity).nextStartedService
        assertNotNull("应已启动前台服务", started)
        assertEquals(
            ConvertService::class.java.name,
            started?.component?.className
        )
    }

    // ---------- 4) 勾选「以后不再提示」后一键直达服务 ----------

    @Test
    fun skippedPreflight_clickStart_launchesServiceDirectly() {
        grantAllPermissions()
        addPendingItem("IMG_D")
        compose.activity.getSharedPreferences("vliveconvert", 0)
            .edit().putBoolean("skip_preflight", true).commit()
        compose.waitForIdle()

        compose.onNodeWithText("开始转换（1）").performClick()
        compose.waitForIdle()

        assertTrue(ConvertCenter.isConverting)
        assertEquals(
            ConvertService::class.java.name,
            shadowOf(compose.activity).nextStartedService?.component?.className
        )
    }

    // ---------- 5) 服务真实跑批：条目必须进入终态（不许卡死在转换中） ----------
    // 注意：Robolectric 的 startForegroundService 只记录 Intent、不实例化服务，
    // 必须用 buildService 显式驱动，才能真实执行 runBatch（真机上由系统拉起等价）

    @Test
    fun serviceBatch_reachesTerminalState() {
        grantAllPermissions()
        addPendingItem("IMG_E")
        compose.activity.getSharedPreferences("vliveconvert", 0)
            .edit().putBoolean("skip_preflight", true).commit()
        compose.waitForIdle()

        compose.onNodeWithText("开始转换（1）").performClick()
        assertTrue(ConvertCenter.isConverting)

        // 显式实例化服务并投递同样的 ACTION_START 意图（真机由系统完成这一步）
        val intent = Intent(compose.activity, ConvertService::class.java)
            .setAction(ConvertService.ACTION_START)
        val controller = Robolectric.buildService(ConvertService::class.java, intent)
        controller.get().onStartCommand(intent, 0, 1)

        // 批次协程真实执行（IO 线程干活、状态回写经 Main looper）：
        // 条目最终必须落到 done 或 failed；停在「转换中」即批次挂死
        val deadline = System.currentTimeMillis() + 20_000
        var item = ConvertCenter.items.firstOrNull()
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            item = ConvertCenter.items.firstOrNull()
            if (item != null && (item.done || item.failed)) break
            Thread.sleep(100)
        }
        assertTrue(
            "条目应进入终态，实际: done=${item?.done} failed=${item?.failed} " +
                "status=${item?.status}",
            item != null && (item!!.done || item.failed)
        )
        controller.destroy()
    }
}
