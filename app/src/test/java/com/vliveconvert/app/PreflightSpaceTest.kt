package com.vliveconvert.app

import com.vliveconvert.app.service.Preflight
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预检「可用空间守卫」的 JVM 回归测试。
 *
 * 背景：真机存储基线长期在 97% 满，大批次转换会在写盘中途撑爆分区——
 * 预检已经算出了源文件总体积，但从未与可用空间比较。守卫的口径：
 * 可用 < 需求 + 1GB 预留 → 拦截；usable 读不到（=0，个别文件系统返回 0）
 * 视为「无法判断」放行——宁可漏拦，不可误拦。
 */
class PreflightSpaceTest {

    private val GB = 1_000_000_000L

    @Test
    fun `usable 不足需求加 1GB 预留时拦截`() {
        assertEquals(
            Preflight.SpaceGuard.INSUFFICIENT,
            Preflight.spaceGuard(neededBytes = 10 * GB, usableBytes = 10 * GB + 999_999_999)
        )
    }

    @Test
    fun `刚好达到需求加预留时放行`() {
        assertEquals(
            Preflight.SpaceGuard.OK,
            Preflight.spaceGuard(neededBytes = 10 * GB, usableBytes = 11 * GB)
        )
    }

    @Test
    fun `usable 为 0 视为无法判断并放行`() {
        assertEquals(Preflight.SpaceGuard.OK, Preflight.spaceGuard(neededBytes = 10 * GB, usableBytes = 0L))
        assertEquals(Preflight.SpaceGuard.OK, Preflight.spaceGuard(neededBytes = 10 * GB, usableBytes = -1L))
    }

    @Test
    fun `需求为 0 时总是放行`() {
        assertEquals(Preflight.SpaceGuard.OK, Preflight.spaceGuard(neededBytes = 0L, usableBytes = 0L))
    }
}
