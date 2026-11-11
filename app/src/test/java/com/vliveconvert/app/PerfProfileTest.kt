package com.vliveconvert.app

import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.service.PerfPreset
import com.vliveconvert.app.service.PerfSettings
import com.vliveconvert.app.service.PerfStore
import com.vliveconvert.app.service.applyPreset
import com.vliveconvert.app.service.autoConcurrency
import com.vliveconvert.app.service.defaultBudgetBytes
import com.vliveconvert.app.service.effectiveBudgetBytes
import com.vliveconvert.app.service.effectiveConcurrency
import com.vliveconvert.app.service.effectiveScanThreads
import com.vliveconvert.app.service.effectiveThumbThreads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 性能设置：自动挡= v2.7 行为（默认），手动挡=用户旋钮；无论哪挡，取值都被夹回合法区间。
 * 内存预算闸与 OOM 自愈是兜底，本表只管「准入上限」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerfProfileTest {

    private val mb = 1024L * 1024L

    @Test
    fun autoConcurrencyMatchesV27Behavior() {
        assertEquals(2, autoConcurrency(384L * mb))
        assertEquals(2, autoConcurrency(512L * mb))
        assertEquals(1, autoConcurrency(383L * mb))
        assertEquals(1, autoConcurrency(256L * mb))
    }

    @Test
    fun autoModeIgnoresManualKnobs() {
        val s = PerfSettings(manual = false, concurrency = 8, scanThreads = 8, thumbThreads = 8)
        assertEquals("自动挡并发与手调无关", 2, s.effectiveConcurrency(384L * mb, 8))
        assertEquals(4, s.effectiveScanThreads())
        assertEquals(4, s.effectiveThumbThreads())
        assertEquals("自动挡预算=堆 45% 口径", defaultBudgetBytes(512L * mb),
            s.effectiveBudgetBytes(512L * mb))
    }

    @Test
    fun manualModeClampsToBounds() {
        val cores = 8
        assertEquals(8, PerfSettings(manual = true, concurrency = 8)
            .effectiveConcurrency(512L * mb, cores))
        assertEquals("9 超上限夹回 8", 8, PerfSettings(manual = true, concurrency = 9)
            .effectiveConcurrency(512L * mb, cores))
        assertEquals("2 核设备并发上限是 2", 2, PerfSettings(manual = true, concurrency = 8)
            .effectiveConcurrency(512L * mb, 2))
        assertEquals(1, PerfSettings(manual = true, concurrency = 1)
            .effectiveConcurrency(512L * mb, cores))
    }

    @Test
    fun manualBudgetClampedToHeapShare() {
        val max = 512L * mb
        assertEquals(128L * mb, PerfSettings(manual = true, memoryLimitMb = 64)
            .effectiveBudgetBytes(max)) // 低于下限夹回 128MB
        assertEquals(
            PerfSettings.memoryUpperMb().toLong() * mb,
            PerfSettings(manual = true, memoryLimitMb = 999999).effectiveBudgetBytes(max))
    }

    @Test
    fun presetsFillManualKnobs() {
        val saver = PerfPreset.Saver.apply()
        assertEquals(1, saver.concurrency)
        assertEquals(2, saver.scanThreads)
        assertEquals(2, saver.thumbThreads)
        assertTrue("省电档预算 ≤ 默认档", saver.memoryLimitMb <= PerfSettings.defaultMemoryMb())

        val perf = PerfPreset.Performance.apply()
        assertEquals(4, perf.concurrency)
        assertEquals(6, perf.scanThreads)
        assertEquals(6, perf.thumbThreads)
    }

    @Test
    fun storeRoundTripsAndDefaultsToAuto() {
        val store = PerfStore(ApplicationProvider.getApplicationContext())
        assertEquals("无记录时默认自动挡", false, store.load().manual)
        store.save(PerfPreset.Performance.apply().copy(manual = true))
        val loaded = store.load()
        assertEquals(true, loaded.manual)
        assertEquals(4, loaded.concurrency)
        assertEquals(6, loaded.scanThreads)
        assertEquals(6, loaded.thumbThreads)
    }

    @Test
    fun glassQualityDefaultsToStandardNoCostChange() {
        val s = PerfSettings()
        assertEquals(com.vliveconvert.app.service.GlassQuality.Standard, s.glassQuality)
        assertEquals(false, s.pauseGlassOnScroll)
        // 标准档必须与旧观感零差异：缩放=1、色散开
        assertEquals(1.0f, s.glassQuality.blurScale)
        assertEquals(1.0f, s.glassQuality.lensScale)
        assertTrue(s.glassQuality.chromatic)
        // 流畅档=去折射保模糊（对齐上游 Low 档语义）：折射整体关闭，模糊保留但降半径
        val saver = com.vliveconvert.app.service.GlassQuality.Saver
        assertTrue(saver.blurScale in 0.01f..0.99f)
        assertEquals(0f, saver.lensScale)
        assertEquals(false, saver.chromatic)
    }

    @Test
    fun everyNonStandardTierActuallyReducesCost() {
        // 防安慰剂档：任何非标准档必须至少在一项光学成本上真的低于标准档
        val standard = com.vliveconvert.app.service.GlassQuality.Standard
        com.vliveconvert.app.service.GlassQuality.entries
            .filter { it != standard }
            .forEach { q ->
                assertTrue("${q.name} 档与标准档成本完全相同（安慰剂档）",
                    q.blurScale < standard.blurScale ||
                        q.lensScale < standard.lensScale ||
                        !q.chromatic)
            }
    }

    @Test
    fun presetApplyPreservesGlassSettings() {
        // 预设只填充转换四旋钮；玻璃画质/滚动降成本是显示设置，不得被静默重置
        val current = PerfSettings(
            glassQuality = com.vliveconvert.app.service.GlassQuality.Saver,
            pauseGlassOnScroll = true)
        PerfPreset.entries.forEach { p ->
            val applied = current.applyPreset(p)
            assertEquals("预设 ${p.name} 改动了玻璃画质",
                com.vliveconvert.app.service.GlassQuality.Saver, applied.glassQuality)
            assertEquals("预设 ${p.name} 改动了滚动降成本开关",
                true, applied.pauseGlassOnScroll)
            assertEquals(true, applied.manual)
        }
    }

    @Test
    fun glassSettingsRoundTrip() {
        val store = PerfStore(ApplicationProvider.getApplicationContext())
        store.save(PerfSettings(glassQuality = com.vliveconvert.app.service.GlassQuality.Saver,
            pauseGlassOnScroll = true))
        val loaded = store.load()
        assertEquals(com.vliveconvert.app.service.GlassQuality.Saver, loaded.glassQuality)
        assertEquals(true, loaded.pauseGlassOnScroll)
        // 复位默认，避免污染其它用例
        store.save(PerfSettings())
    }
}