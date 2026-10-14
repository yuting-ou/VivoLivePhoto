package com.vliveconvert.app

import android.Manifest
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.service.ConvertCenter
import com.vliveconvert.app.ui.ConvertItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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
 * 冷启动路径上的「点击开始转换无反应」（v1.7.9 用户反馈，1.7.9 仍存在）。
 *
 * 与 [ConvertFlowUiTest] 的分工：那份用例授权后 `recreate()`，并且直接往
 * `ConvertCenter.items` 塞条目。`recreate()` 会新建 Activity 实例、把所有字段重置为初值，
 * 「进程启动后的第一次交互」就消失了；直接塞 items 又绕过了选择器「添加」的回调，
 * 也不让 onCreate 里那个「读旧队列」的 IO 协程与新条目发生关系。
 *
 * 本类补上三点：权限在 Activity 首次组合**之前**授予（不 recreate）、
 * 条目经生产代码 `addPickedItems()` 入队、旧队列在 `onCreate` 之前就落盘。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdStartConvertTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    /**
     * 外层规则：先把权限授予到位，之后内层规则才启动 Activity。
     * 顺序颠倒就退化成「未授权启动 + recreate 补救」——正是漏掉这个 bug 的写法。
     */
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

    private fun mediaItem(name: String, jpg: File) = MediaItem(
        id = name.hashCode().toLong(),
        path = jpg.absolutePath,
        name = "$name.jpg",
        bucketId = 1L,
        dateTaken = 1_754_000_000_000L,
        dateModified = 1_754_000_000L,
        size = if (jpg.exists()) jpg.length() else 6L
    )

    /** 物理存在的双文件样本：jpg + 同目录同名非空 mp4（Preflight 要 stat 得到） */
    private fun sampleItem(name: String): MediaItem {
        val dir = File(activity.filesDir, "samples").apply { mkdirs() }
        val jpg = File(dir, "$name.jpg")
        if (!jpg.exists()) {
            jpg.writeBytes(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte()))
            File(dir, "$name.mp4").writeBytes(ByteArray(16))
        }
        return mediaItem(name, jpg)
    }

    /** 走生产代码入队：选择器「添加」按钮的 onClick 调的就是它（主线程） */
    private fun addThroughPicker(vararg names: String) {
        val picked = names.map { sampleItem(it) }
        val method = MainActivity::class.java
            .getDeclaredMethod("addPickedItems", List::class.java)
            .apply { isAccessible = true }
        activity.runOnUiThread { method.invoke(activity, picked) }
        compose.waitForIdle()
    }

    /** 反射调用 Activity 的无参私有方法（清理只在 onCreate 的 IO 协程里跑，测试需单独驱动） */
    private fun invokeNoArg(name: String) {
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }
            .invoke(activity)
    }

    /** 弹窗在 Robolectric 下是独立 window，语义查询不可靠，改读 Activity 内部状态 */
    private fun internalState(name: String): Any? {
        val f = try {
            MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val v = f.get(activity)
        return if (v is androidx.compose.runtime.State<*>) v.value else v
    }

    /** 点主按钮并检查「有反应」：四种可见信号全无 = 用户视角的「点了没反应」 */
    private fun clickStartExpectFeedback(buttonLabel: String) {
        ConvertCenter.statusText = ""
        compose.onNodeWithText(buttonLabel).performClick()
        compose.waitForIdle()

        val preflightShown = internalState("showPreflightDialog") == true
        val gateShown = internalState("showLocationDialog") == true ||
            internalState("showLocationSettingsDialog") == true
        val started = ConvertCenter.isConverting
        val statusSpoke = ConvertCenter.statusText.isNotEmpty()

        assertTrue(
            "点击「$buttonLabel」无任何反应：预检=$preflightShown 门禁=$gateShown " +
                "转换中=$started 状态行未变=${!statusSpoke}",
            preflightShown || gateShown || started || statusSpoke
        )
    }

    @Before
    fun resetProcessSingleton() {
        // ConvertCenter 是进程级单例，Robolectric 不隔离它：不清会把上一条用例的条目带进来
        activity.runOnUiThread {
            ConvertCenter.items.clear()
            ConvertCenter.isConverting = false
            ConvertCenter.progressDetail = ""
            ConvertCenter.statusText = ""
        }
        compose.waitForIdle()
    }

    // ---------- 夹具自证 ----------

    /** 权限先于首次组合生效、清空队列后应渲染主界面空状态，否则等于没测冷启动 */
    @org.junit.Test
    fun coldStart_rendersMainWithoutRecreate() {
        compose.onNodeWithText("还没有选择照片").assertExists()
    }

    // ---------- 路径 1：冷启动，全新加一张 ----------

    @org.junit.Test
    fun coldStart_firstAdd_thenStartConvert_hasVisibleFeedback() {
        addThroughPicker("IMG_COLD_1")
        compose.onNodeWithText("开始转换（1）").assertExists()
        clickStartExpectFeedback("开始转换（1）")
    }

    // ---------- 路径 2：旧队列里这张已是 done，用户重启后再加同一张 ----------

    /**
     * `addPickedItems` 按 id 去重（MainActivity.kt:901）：旧队列这条是 done，
     * 新添加被静默丢弃 → pendingCount=0 → 主按钮 `enabled=false`（MainScreen.kt:379），
     * 点它连 onClick 都不派发，界面也没有任何一处解释「为什么不能点」。
     */
    @org.junit.Test
    fun coldStart_leftoverDoneQueue_reAddSamePhoto_hasVisibleFeedback() {
        // 等价于 loadQueue 恢复完的状态：昨天转过这张，队列里留着它的 done 记录
        val yesterday = sampleItem("IMG_SAME")
        activity.runOnUiThread {
            ConvertCenter.items.add(
                ConvertItem(item = yesterday, status = "完成：已导出到输出目录", done = true))
        }
        compose.waitForIdle()

        addThroughPicker("IMG_SAME")

        assertTrue("同一张不应被重复入队", ConvertCenter.items.size == 1)
        clickStartExpectFeedback("开始转换")
    }

    // ---------- 路径 3：与「关选择器」同一帧提交入队（撞转场） ----------

    /**
     * PickerScreen 的「添加」onClick = `{ showPicker = false; addPickedItems(picked) }`
     * （MainActivity.kt:448-451）：关屏与入队同一帧提交，且正撞上 Picker→Main 的转场。
     * 这里按同一形状写这两处状态，看主按钮是否照常可点、点了是否有反应。
     */
    @org.junit.Test
    fun coldStart_addCommittingTogetherWithScreenSwitch_hasVisibleFeedback() {
        val picked = listOf(sampleItem("IMG_FRAME_1"))
        val showPicker = MainActivity::class.java
            .getDeclaredField("showPicker\$delegate").apply { isAccessible = true }
        val add = MainActivity::class.java
            .getDeclaredMethod("addPickedItems", List::class.java).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        fun setShowPicker(v: Boolean) {
            (showPicker.get(activity) as androidx.compose.runtime.MutableState<Boolean>).value = v
        }
        setShowPicker(true)
        compose.waitForIdle()
        activity.runOnUiThread {
            setShowPicker(false)
            add.invoke(activity, picked)
        }
        compose.waitForIdle()

        compose.onNodeWithText("开始转换（1）").assertExists()
        clickStartExpectFeedback("开始转换（1）")
    }

    // ---------- 路径 4：冷启动清理 vs 第一批转换（异步竞争） ----------

    /**
     * `onCreate` 在 IO 线程上清两样东西：`cacheDir/output` 下的全部文件，
     * 以及 MediaStore 里**所有** `IS_PENDING=1` 的行（MainActivity.kt:1292）。
     * 而 `ConvertService` 的暂存目录正是 `cacheDir/output`（ConvertService.kt:102），
     * 产物导出过程中就是 `IS_PENDING=1`（MediaExport.kt:66，校验通过后才置 0）。
     *
     * 清理与用户第一次点「开始转换」之间没有任何互斥：谁先跑完决定结果会不会被吃掉。
     * 冷启动清理只该清「本次启动之前」的半成品——所以本次启动之后新建的 pending
     * 行必须活着。
     */
    @org.junit.Test
    fun coldStartCleanup_keepsPendingRowCreatedByCurrentBatch() {
        val resolver = activity.contentResolver
        val collection =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_INFLIGHT_9999.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VLiveConvert")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            // 即便显式声明「上次会话」的时间，ShadowMediaStore 也按自己的假时钟盖 DATE_ADDED，
            // 所以这里只断言「本次启动之后的记录不许删」；删除方向由纯函数用例钉住
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L - 3_600L)
        })
        assertTrue("应能建立一条 IS_PENDING=1 的导出中行", uri != null)

        invokeNoArg("cleanupPendingMediaStore")

        assertTrue("本次启动之后正在导出的产物记录被冷启动清理删掉了",
            resolver.query(uri!!, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.moveToFirst() } == true)
    }

    /**
     * 删除方向不能只靠集成用例：Robolectric 不让我控制 DATE_ADDED，
     * 万一判据退化成「一律保留」，上面那条照样绿——清理就再也不会删任何东西了。
     */
    @org.junit.Test
    fun stalePendingRow_boundaryFavorsKeeping() {
        val launch = 1_700_000_000_500L // 秒 = 1_700_000_000
        assertTrue("上次会话（早于本次启动一秒以上）的残留必须判为可删",
            isStalePendingMediaRow(1_700_000_000L - 1, launch))
        assertFalse("与本次启动同一秒：可能是正在跑的批次，保留",
            isStalePendingMediaRow(1_700_000_000L, launch))
        assertFalse("本次启动之后新建的，保留",
            isStalePendingMediaRow(1_700_000_000L + 3_600, launch))
        assertFalse("DATE_ADDED 读不到（0）时保留：宁可漏清不误删",
            isStalePendingMediaRow(0L, launch))
    }

    /** 清理的另一半同理：暂存目录里只许删「本次启动之前」的文件 */
    @org.junit.Test
    fun coldStartCleanup_keepsStagingFileWrittenByCurrentBatch() {
        val dir = File(activity.cacheDir, "output").apply { mkdirs() }
        val stale = File(dir, "IMG_STALE.jpg").apply { writeBytes(ByteArray(4)) }
        stale.setLastModified(System.currentTimeMillis() - 60_000)
        val inFlight = File(dir, "IMG_INFLIGHT.jpg").apply { writeBytes(ByteArray(4)) }

        invokeNoArg("cleanupStaleStagingFiles")

        assertFalse("上次残留的暂存文件应被清掉", stale.exists())
        assertTrue("批次正在组装的暂存文件被冷启动清理删掉了", inFlight.exists())
    }
}
