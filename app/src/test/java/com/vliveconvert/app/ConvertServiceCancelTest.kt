package com.vliveconvert.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vliveconvert.app.service.ConvertService
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 通知栏「取消」动作的服务侧分支：空闲时收到 CANCEL 必须安静停服——
 * 既不能把服务挂成前台（没有批次，挂前台只会留下一条假通知），也不能崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvertServiceCancelTest {

    private val app get() = ApplicationProvider.getApplicationContext<VliveApp>()

    @Test
    fun cancelWhenIdleStopsServiceQuietly() {
        val controller = Robolectric.buildService(ConvertService::class.java)
        controller.withIntent(
            Intent(app, ConvertService::class.java).setAction(ConvertService.ACTION_CANCEL))
        controller.create()
        controller.startCommand(0, 0)

        assertTrue("空闲 CANCEL 应直接停服", shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }
}
