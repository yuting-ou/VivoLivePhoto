package com.vliveconvert.app

import android.Manifest
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.ConvertCenter
import com.vliveconvert.app.ui.ConvertItem
import com.vliveconvert.app.ui.MainScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.7.12 三项反馈层改动的回归测试。
 *
 * 动因是真机实测：系统动画缩放三项全为 1.0（排除「手机关了动画」）、帧统计 0 janky
 * （排除卡顿），但单张照片 ~300ms 就转完——**「转换中」态的存活时间短于底栏过渡时长**，
 * 于是动效来不及播，观感只剩「原地闪一下」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvertFeedbackTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    /** 权限必须先于首次组合，否则界面停在授权页、点不到主按钮 */
    @get:Rule
    val chain: TestRule = RuleChain.outerRule(object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                shadowOf(ApplicationProvider.getApplicationContext<VliveApp>()).grantPermissions(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                )
                base.evaluate()
            }
        }
    }).around(compose)

    private val activity get() = compose.activity

    private fun field(name: String): Any? {
        val cls = MainActivity::class.java
        return try {
            cls.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(activity)
        } catch (_: NoSuchFieldException) {
            cls.getDeclaredField(name).apply { isAccessible = true }.get(activity)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setState(name: String, value: Any?) =
        ((field(name) as MutableState<Any?>).also { it.value = value })

    private fun readState(name: String): Any? = (field(name) as? State<*>)?.value

    /** 造一张真实存在的源文件（预检要 stat 得到）并入队 */
    private fun addPendingItem(name: String) {
        val dir = File(activity.filesDir, "samples").apply { mkdirs() }
        val jpg = File(dir, "$name.jpg")
        if (!jpg.exists()) {
            jpg.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte()))
            File(dir, "$name.mp4").writeBytes(ByteArray(16))
        }
        val mi = MediaItem(name.hashCode().toLong(), jpg.absolutePath, "$name.jpg",
            1L, 1_754_000_000_000L, 1_754_000_000L, jpg.length())
        activity.runOnUiThread {
            ConvertCenter.items.clear()
            ConvertCenter.items.add(ConvertItem(item = mi))
            ConvertCenter.isConverting = false
            ConvertCenter.finishedBeat = false
            ConvertCenter.statusText = ""
        }
        compose.waitForIdle()
    }

    private fun setPrefs(vararg pairs: Pair<String, Any>) {
        val e = activity.getSharedPreferences("vliveconvert", 0).edit()
        pairs.forEach { (k, v) ->
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.commit()
    }

    // ---------- 1) 删除原图时，「以后不再提示」必须失效 ----------

    /**
     * 真机上就是这样删掉了用户一张原图：勾过「以后不再提示」+「删除原图=开」时，
     * 一次单击直接开删、零确认。这一条钉住「不可逆操作不许被跳过」。
     */
    @Test
    fun deleteOriginalOn_neverSkipsPreflight() {
        setPrefs("skip_preflight" to true, "delete_original" to true)
        setState("deleteOriginal", true)
        addPendingItem("IMG_DEL_ON")

        compose.onNodeWithText("开始转换（1）").performClick()
        compose.waitForIdle()

        assertTrue("删原图开着时必须弹影响面确认，不得跳过",
            readState("showPreflightDialog") == true)
        assertFalse("确认前不得开始删除性转换", ConvertCenter.isConverting)
    }

    // ---------- 4) 真实批次结束时，节拍必须由服务自己举起 ----------

    /**
     * 上面那条用例是手置 `finishedBeat` 测停留与收时；这条补住另一半：
     * **谁把它置上**。少了这条，把 ConvertService 里那行赋值守卫删掉也不会有人报警，
     * 而用户看到的就又变回「闪一下」。
     *
     * 冻结 compose 时钟，让清除用的 delay(450) 不会在断言前自己跑完。
     */
    @Test
    fun realBatch_completionRaisesFinishedBeat() {
        setPrefs("skip_preflight" to true, "delete_original" to false)
        setState("deleteOriginal", false)
        addPendingItem("IMG_REAL_BEAT")
        compose.mainClock.autoAdvance = false

        compose.onNodeWithText("开始转换（1）").performClick()
        compose.waitForIdle()
        assertTrue(ConvertCenter.isConverting)

        // Robolectric 不会真正拉起服务，显式投递同样的 ACTION_START
        val intent = android.content.Intent(compose.activity,
            com.vliveconvert.app.service.ConvertService::class.java)
            .setAction(com.vliveconvert.app.service.ConvertService.ACTION_START)
        val controller = org.robolectric.Robolectric.buildService(
            com.vliveconvert.app.service.ConvertService::class.java, intent)
        controller.get().onStartCommand(intent, 0, 1)

        val deadline = System.currentTimeMillis() + 20_000
        var item: ConvertItem? = null
        // 等的是「批次收尾」而不是「条目终态」：条目先 done，随后才有一次
        // 统一的 Main 写入（isConverting=false + finishedBeat=true）。
        // 早一步跳出，断言的就是还没发生的写入——那是探针抢跑，不是产品缺陷
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            item = ConvertCenter.items.firstOrNull()
            if (item != null && (item.done || item.failed) && !ConvertCenter.isConverting) break
            Thread.sleep(100)
        }
        assertTrue("批次应已跑到终态并收尾，实际 status=${item?.status} " +
            "isConverting=${ConvertCenter.isConverting}",
            item != null && (item!!.done || item.failed) && !ConvertCenter.isConverting)

        assertTrue("批次结束后应举起「完成节拍」，否则底栏还是会一闪而过",
            ConvertCenter.finishedBeat)

        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertFalse("节拍应在一段时间后自动收掉", ConvertCenter.finishedBeat)
        controller.destroy()
    }

    /** 对照组：不删原图时「不再提示」照旧生效——否则这个勾选就白给了 */
    @Test
    fun deleteOriginalOff_stillSkipsPreflight() {
        setPrefs("skip_preflight" to true, "delete_original" to false)
        setState("deleteOriginal", false)
        addPendingItem("IMG_DEL_OFF")

        compose.onNodeWithText("开始转换（1）").performClick()
        compose.waitForIdle()

        assertTrue("不删原图时应按用户选择跳过确认、直接开转", ConvertCenter.isConverting)
        assertFalse("跳过时不应再弹确认", readState("showPreflightDialog") == true)

        ConvertCenter.isConverting = false   // 收尾，别把状态留给下一条用例
    }

    // ---------- 2) 完成节拍要停留，且用墙钟而不是动画时长 ----------

    /** 节拍期间底栏仍是「进度态」；超过最短可见时长后自动退回主按钮 */
    @Test
    fun finishedBeat_holdsThenClearsOnWallClock() {
        addPendingItem("IMG_BEAT")
        ConvertCenter.progress = 1f
        ConvertCenter.progressDetail = "已处理 1/1"
        ConvertCenter.finishedBeat = true
        compose.waitForIdle()

        assertTrue("节拍期间状态位应为真", ConvertCenter.finishedBeat)
        compose.onNodeWithText("已处理 1/1").assertExists()

        // delay(450) 是墙钟延时，不受系统动画缩放影响——这正是选它而不是加长动画的原因
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertFalse("超过最短可见时长后节拍应自动收掉", ConvertCenter.finishedBeat)
    }
}
