package com.vliveconvert.app

import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.picker.MediaRepo
import com.vliveconvert.app.picker.PickerScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 扫描引擎「状态收敛」的回归测试。
 *
 * 背景：原实现只在 diffRefresh 返回 true（确实有待扫项）时才走到收尾分支去置
 * `running=false` / `completed=true`。于是两条真实路径会让状态永不复位：
 *  1) 相册为空 / 查询无结果 → UI 的完成轮询（每 200ms）永不终止（空转耗电），
 *     且进度条永久停在「扫描中」；
 *  2) 扫描中途切走（job 被 cancel）→ join() 抛 CancellationException，
 *     收尾分支被跳过，`running` 残留为 true；切回时若不复位，同样卡住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PickerScannerTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()

    /**
     * 用测试调度器接管 Main。扫描体在 `withContext(Main)` 里复位状态；
     * 若依赖 Robolectric 的 Looper，Dispatchers.Main 会被静态绑定到**首个**测试沙箱的
     * Looper，整套测试一起跑时后续用例的 idle() 推不动它（单跑通过、全量偶发失败）。
     * 接管为 UnconfinedTestDispatcher 后 inline 执行，完全确定、不依赖 Looper。
     */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 空相册（无任何图片）也必须收敛到 completed，且 running 归位 */
    @Test
    fun emptyAlbumConvergesToCompleted() {
        val scanner = PickerScanner(MediaRepo(app.contentResolver))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val emptyBucket = 999_999L

        scanner.enter(emptyBucket, scope)
        val st = scanner.stateOf(emptyBucket)

        val deadline = System.currentTimeMillis() + 5_000
        while (!st.completed && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertTrue(
            "空相册也必须收敛到 completed，否则 UI 会每 200ms 空转轮询、进度条永久卡在「扫描中」",
            st.completed)
        assertFalse("收敛后 running 必须为 false", st.running)
        scope.cancel()
    }

    /** 中断扫描后复位进度时，残留的 running 标志必须被清掉 */
    @Test
    fun resetProgressClearsResidualRunningFlag() {
        val st = PickerScanner.ScanState()
        st.running = true
        st.resetProgress()
        assertFalse("resetProgress 应收敛 running（中断路径不会走收尾分支）", st.running)
        assertTrue("results 应被清空", st.results.isEmpty())
    }
}
