package com.vliveconvert.app

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.service.BatchWakeLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.annotation.Config

/**
 * 批次 wakelock 的持锁语义：acquire 后真实持有、release 后放下；
 * 重复 acquire 幂等、release 在未持有时也必须安全（取消/销毁竞态）。
 *
 * 为什么需要它：前台服务**不**阻止 CPU 休眠——熄屏长批次会被 Doze 限速到
 * 近乎停滞（真机实测）。而 wakelock 若漏标权限会抛 SecurityException，
 * 实现里为不阻塞转换把它吞掉——于是唯一能大声报警的就是这份测试与
 * ManifestContractTest 的权限断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchWakeLockTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val powerManager get() = app.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Before
    fun isolateWakeLockState() = ShadowPowerManager.clearWakeLocks()

    @Test
    fun acquireHoldsAndReleaseDrops() {
        BatchWakeLock.acquire(app)
        assertTrue("acquire 后必须真实持锁", ShadowPowerManager.getLatestWakeLock()!!.isHeld)

        BatchWakeLock.release(app)
        assertFalse("release 后必须放锁", ShadowPowerManager.getLatestWakeLock()!!.isHeld)
    }

    @Test
    fun doubleAcquireIsIdempotent() {
        BatchWakeLock.acquire(app)
        BatchWakeLock.acquire(app)
        assertTrue(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        BatchWakeLock.release(app)
        assertFalse(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
    }

    @Test
    fun releaseWithoutAcquireIsSafe() {
        BatchWakeLock.release(app)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue("未 acquire 时不得有任何锁被持有", lock == null || !lock.isHeld)
        assertFalse("PowerManager 不应残留持锁记录",
            ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
    }
}
