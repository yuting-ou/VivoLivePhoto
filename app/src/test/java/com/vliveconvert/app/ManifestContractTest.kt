package com.vliveconvert.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 清单契约测试：把「AndroidManifest.xml 必须声明什么」变成可执行的断言。
 *
 * 为什么需要它（v1.3.0 事故）：
 * 清单里声明了 `<service android:foregroundServiceType="dataSync">`，却漏了
 * `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` 权限声明。Android 9+ 起
 * `startForeground()` 必须持有前者，Android 14+ 起还必须持有服务类型对应的后者，
 * 缺失时抛 SecurityException —— 表现为「点击开始转换立即闪退」。
 *
 * 为什么 JVM 单测抓不到、lint 也抓不到：
 * - 既有单测只覆盖 `core/` 的字节级转换管道，不加载清单、不实例化 Activity/Service；
 * - 实测 lint（AGP 9.5 默认规则集）**不会**报告该权限缺失（已用移除权限的清单验证过）。
 * 故此测试直接解析清单文本做断言，作为该类问题的固定守卫。
 *
 * 运行目录：Gradle 单元测试的工作目录为模块目录（app/），故用相对路径读取。
 */
class ManifestContractTest {

    private val manifest: String by lazy {
        val f = File("src/main/AndroidManifest.xml")
        assertTrue(
            "找不到清单文件（工作目录=${File("").absolutePath}）：${f.absolutePath}",
            f.exists()
        )
        f.readText()
    }

    /** 提取全部 <uses-permission android:name="..." /> 声明 */
    private fun declaredPermissions(): Set<String> =
        Regex("""<uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun foregroundServicePermissionsDeclared() {
        val perms = declaredPermissions()
        // ConvertService 是前台服务：Android 9+ 必须 FOREGROUND_SERVICE
        assertTrue(
            "清单缺少 FOREGROUND_SERVICE 权限：前台服务调用 startForeground() 会抛 " +
                "SecurityException 导致点击「开始转换」闪退",
            "android.permission.FOREGROUND_SERVICE" in perms
        )
        // Android 14+ 还必须持有服务类型对应的权限
        assertTrue(
            "清单缺少 FOREGROUND_SERVICE_DATA_SYNC 权限：服务声明了 " +
                "foregroundServiceType=\"dataSync\"，Android 14+ 缺该权限会闪退",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in perms
        )
    }

    /**
     * 服务类型与其所需权限必须配对：解析每个 `<service>` 的 foregroundServiceType，
     * 逐个校验对应权限已声明——避免以后新增/更换服务类型时再次漏声明。
     */
    @Test
    fun everyForegroundServiceTypeHasMatchingPermission() {
        val typeToPermission = mapOf(
            "dataSync" to "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "mediaPlayback" to "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "mediaProjection" to "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
            "camera" to "android.permission.FOREGROUND_SERVICE_CAMERA",
            "microphone" to "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "location" to "android.permission.FOREGROUND_SERVICE_LOCATION",
            "connectedDevice" to "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "health" to "android.permission.FOREGROUND_SERVICE_HEALTH",
            "remoteMessaging" to "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING",
            "shortService" to "android.permission.FOREGROUND_SERVICE_SHORT_SERVICE",
            "specialUse" to "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "systemExempted" to "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED"
        )
        val perms = declaredPermissions()

        val serviceTags = Regex("""<service\b[^>]*>""").findAll(manifest).map { it.value }.toList()
        assertTrue("清单中应至少声明一个 <service>", serviceTags.isNotEmpty())

        var checked = 0
        for (tag in serviceTags) {
            val types = Regex("""android:foregroundServiceType="([^"]+)"""")
                .find(tag)?.groupValues?.get(1) ?: continue
            for (type in types.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
                val needed = typeToPermission[type]
                assertTrue(
                    "服务声明了未登记的前台服务类型「$type」——请在本测试的映射表中补充其所需权限",
                    needed != null
                )
                assertTrue(
                    "服务使用 foregroundServiceType=\"$type\"，但清单未声明 $needed",
                    needed in perms
                )
                checked++
            }
        }
        assertTrue("应至少校验到一个前台服务类型声明", checked > 0)
    }

    /**
     * 运行时申请的权限必须在清单中声明：未声明的权限申请会被系统直接判为拒绝，
     * 且不会有任何提示——典型症状是「权限申请弹不出来、功能静默不可用」。
     */
    @Test
    fun runtimeRequestedPermissionsAreDeclared() {
        val perms = declaredPermissions()
        // MainActivity.requiredReadPermissions() 中的权限清单
        val requested = listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.ACCESS_MEDIA_LOCATION",
            "android.permission.POST_NOTIFICATIONS"
        )
        val missing = requested.filter { it !in perms }
        assertTrue(
            "以下权限在代码中运行时申请，但未在清单声明（申请会被静默拒绝）：$missing",
            missing.isEmpty()
        )
    }

    /** 位置权限缺失会导致系统在读取层剥离 GPS EXIF——必须持续声明，不能误删 */
    @Test
    fun mediaLocationPermissionDeclared() {
        assertTrue(
            "清单缺少 ACCESS_MEDIA_LOCATION：系统会在应用读取照片时剥离 GPS，" +
                "转换产物将丢失地点信息",
            "android.permission.ACCESS_MEDIA_LOCATION" in declaredPermissions()
        )
    }
}
