package com.vliveconvert.app

import android.Manifest
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「输出目录」编辑框的可达性。
 *
 * 单独一个类、用**默认视口**（历史原因：旧版含文本框的 AlertDialog 在自定义视口下
 * 会让 Compose 测试 60 秒进不了 idle；现在弹窗已改成应用内玻璃浮层，仍保留默认视口
 * 覆盖真实入口）。此外钉住「设置页 + 预测性返回处理器」叠加时，返回键只关弹窗。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputPathDialogTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    /** 权限必须在 Activity 首次组合之前授予：hasReadPermission() 不是可观察状态，
     *  启动后再授权界面不会自己离开授权页 */
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

    @Suppress("UNCHECKED_CAST")
    private fun flag(name: String): Boolean {
        val f = MainActivity::class.java.getDeclaredField("${name}\$delegate")
            .apply { isAccessible = true }
        return (f.get(compose.activity) as MutableState<Boolean>).value
    }

    @Test
    fun firstSettingsRow_opensOutputPathDialog() {
        compose.waitForIdle()

        // 「输出目录」是设置页第一行，默认视口内可见
        compose.onNodeWithText("设置 ›").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("输出目录").performClick()
        compose.waitForIdle()

        assertTrue("点「输出目录」必须弹出编辑框", flag("showOutputPathDialog"))
        compose.onNodeWithText("相对主存储的路径，导出的单文件实况会保存到这里；留空恢复默认。")
            .assertExists()

        // 设置页叠着预测性返回处理器，弹窗自己的 BackHandler 必须在它前面消费
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertFalse("返回键应只关弹窗、不退出设置页", flag("showOutputPathDialog"))
        assertTrue("设置页本身应仍在", flag("showSettings"))
    }
}
