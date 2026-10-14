package com.vliveconvert.app

import android.Manifest
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
 * 单独一个类、用**默认视口**：含文本框的 AlertDialog 在自定义视口下会让 Compose 测试
 * 60 秒进不了 idle（用裸 Activity 渲染同样内容的对话框也复现同样挂起，
 * 与本项目代码无关，属 Robolectric/Compose 测试框架限制）。放在这里是为了不假装它"测过了"。
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
    }
}
