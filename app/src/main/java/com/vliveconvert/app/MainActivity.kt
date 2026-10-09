package com.vliveconvert.app

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.vliveconvert.app.R
import com.vliveconvert.app.convert.MediaExport
import com.vliveconvert.app.core.PhotoTime
import com.vliveconvert.app.picker.AlbumInfo
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.picker.MediaRepo
import com.vliveconvert.app.picker.PickerScanner
import com.vliveconvert.app.picker.PickerScreen
import com.vliveconvert.app.picker.SingleLiveScanner
import com.vliveconvert.app.service.ConvertCenter
import com.vliveconvert.app.service.ConvertService
import com.vliveconvert.app.service.Preflight
import com.vliveconvert.app.ui.AboutScreen
import com.vliveconvert.app.ui.ConvertItem
import com.vliveconvert.app.ui.FixTimeScreen
import com.vliveconvert.app.ui.MainScreen
import com.vliveconvert.app.ui.PermissionScreen
import com.vliveconvert.app.ui.SettingsScreen
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** 主界面栈：权限页 → 主页 → 选择器 / 修复时间 / 设置 / 关于（用于转场方向判定） */
private enum class Screen { Permission, Main, Picker, FixTime, Settings, About }

/**
 * 主界面：转换队列与进度的真实状态在 [ConvertCenter]（进程级单例），
 * 批量转换由前台服务 ConvertService 执行——界面销毁/切后台不影响转换。
 * Activity 负责权限流、界面导航、系统弹窗（回收站确认）与历史文件操作。
 */
class MainActivity : ComponentActivity() {

    // 内置选择器
    private lateinit var mediaRepo: MediaRepo
    private lateinit var scanner: PickerScanner
    private var showPicker by mutableStateOf(false)
    private var pickerAlbums by mutableStateOf<List<AlbumInfo>>(emptyList())

    // 权限
    private var showSettingsDialog by mutableStateOf(false)
    // 位置权限缺失时的转换门禁弹窗（GPS 会被系统读取层剥离，需用户显式确认）
    private var showLocationDialog by mutableStateOf(false)
    // 所有文件访问权限引导弹窗（删除原图 / 修复时间功能需要时提示）
    private var showAllFilesDialog by mutableStateOf(false)
    // 权限授予后要执行的动作（默认进入内置选择器）
    private var pendingPermissionAction: (() -> Unit)? = null
    // 所有文件访问权限授予后要执行的动作（如进入修复时间界面）
    private var pendingAfterAllFiles: (() -> Unit)? = null

    // 转换后删除原图（持久化开关；批次执行读同一份偏好）
    private var deleteOriginal by mutableStateOf(false)
    // 转换后移到相机相册（持久化开关，默认开启）：导出文件直接写入 DCIM/Camera
    private var moveToCamera by mutableStateOf(true)
    private var deleteBaseStatus = ""
    private var pendingDeleteCount = 0
    /** 本次待写入回收站的 URI（确认成功后转持久化恢复记录） */
    private var lastTrashUris: List<String> = emptyList()
    /** 回收站确认弹窗是否已在途（防止 onStart 重复拉起） */
    private var trashRequestInFlight = false
    /** 回收站中的原图记录数（未授权所有文件访问的删除路径），应用内可恢复（30 天内） */
    private var pendingRestoreCount by mutableIntStateOf(0)

    // 崩溃日志
    private var crashLogCount by mutableIntStateOf(0)

    // 修复文件时间（把单文件实况的「修改时间」按文件名时间修正；相册式选择，同双文件选择器）
    private var showFixTime by mutableStateOf(false)
    private lateinit var singleLiveScanner: SingleLiveScanner
    private var fixAlbums by mutableStateOf<List<AlbumInfo>>(emptyList())
    private var fixStatus by mutableStateOf("")
    private var isFixing by mutableStateOf(false)
    private var fixProgress by mutableFloatStateOf(0f)
    private var fixSelectionReset by mutableIntStateOf(0)

    // 设置页（收纳低频项）与状态详情弹窗
    private var showSettings by mutableStateOf(false)
    private var showStatusDetail by mutableStateOf(false)
    // 转换前的影响面预估弹窗
    private var showPreflightDialog by mutableStateOf(false)
    private var preflight: Preflight.Info? = null
    private var skipPreflightNext by mutableStateOf(false)
    // 关于 / 开源许可页（GPL-3.0 合规：许可证文本随包内置）
    private var showAbout by mutableStateOf(false)

    // 自定义输出目录（MediaStore 相对路径，默认 Pictures/VLiveConvert）
    private var outputRelPath by mutableStateOf("Pictures/VLiveConvert")
    private var showOutputPathDialog by mutableStateOf(false)
    private var outputPathInput by mutableStateOf("")
    // 输出文件批量移动到 DCIM/Camera
    private var isMovingOutputs by mutableStateOf(false)

    // 动态照片 = 图片 + 伴生视频，必须同时申请图片与视频读取权限
    // （双文件格式需要直接读取同目录 .mp4，缺视频权限会报 EACCES）；
    // 顺带申请通知权限（转换通知需要，拒绝也不阻塞转换）
    private fun requiredReadPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    // 媒体访问能力：任一媒体读取权限授予即可进入选择器（通知/位置权限不算）
    private fun hasReadPermission(): Boolean =
        requiredReadPermissions()
            .filter {
                it != Manifest.permission.ACCESS_MEDIA_LOCATION &&
                    it != Manifest.permission.POST_NOTIFICATIONS
            }
            .any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * 位置权限（ACCESS_MEDIA_LOCATION）：缺失时系统（MediaStore/FUSE）会在读取层
     * 实时剥离照片 GPS EXIF，字节级无损管道将原样保留脱敏结果——转换产物丢失地点
     * 信息且无法事后找回。因此转换前必须显式确认，而不是静默继续。
     */
    private fun hasMediaLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 位置权限缺失（Compose 状态：驱动主界面警示条与门禁弹窗） */
    private var locationDenied by mutableStateOf(false)
    /** 用户已在本会话中明确选择「仍要转换」（缺位置权限）：后续批次不再重复门禁 */
    private var locationForceChosen = false
    /** 位置权限就绪后要继续的动作（开始转换 / 重新转换）；系统设置返回时消费 */
    private var pendingLocationAction: (() -> Unit)? = null
    /** 待权限动作是「重新转换」：无权限时执行毫无意义，设置引导弹窗不提供「仍要转换」 */
    private var pendingActionIsReconvert = false
    /** 系统不再弹授权框时引导设置的门禁弹窗（与普通门禁弹窗区分文案） */
    private var showLocationSettingsDialog by mutableStateOf(false)

    /**
     * 单独申请位置权限：绝不与媒体权限混在同一请求——
     * ① Android 14+ 的照片选择器流程可能吞掉同批其他权限的授权弹窗
     *   （部分 OEM 上 ACCESS_MEDIA_LOCATION 的框根本弹不出来）；
     * ② 重复申请已授予的媒体权限会再次拉起照片选择器，干扰用户。
     */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationDenied = !hasMediaLocationPermission()
        val action = pendingLocationAction
        if (granted) {
            ConvertCenter.statusText = "已授予「位置」权限，转换将保留照片 GPS 地点信息"
            pendingLocationAction = null
            action?.invoke()
        } else if (action != null) {
            // 重转/转换在等权限：用户拒绝 → 引导系统设置。
            // 保留挂起动作：设置页授予后返回时自动继续；弹窗「取消/仍要转换」时消费
            showLocationSettingsDialog = true
        } else if (shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            // 警示条上的主动授权：拒绝 → 回门禁弹窗（可再授权或「仍要转换」）
            showLocationDialog = true
        } else {
            // 「不再询问」或 OEM 限制不弹框 → 引导系统设置手动开启
            showLocationSettingsDialog = true
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        locationDenied = !hasMediaLocationPermission()
        val granted = result.values.any { it }
        if (granted) {
            // 伴生 MP4 依赖完整视频读取权限；「选择照片」部分授权（VISUAL_USER_SELECTED）
            // 并不包含视频访问，此时会找不到伴生视频，须明确提示而非误报成功
            val videoGranted = result[Manifest.permission.READ_MEDIA_VIDEO] == true
            val locationHint = if (!hasMediaLocationPermission())
                "；未授予「位置」权限：转换将丢失照片 GPS 地点信息" else ""
            ConvertCenter.statusText = (if (videoGranted) "已获得读取照片和视频权限"
                         else "已授权照片，但缺少完整视频权限：无法找到双文件实况的伴生视频，" +
                              "请到系统设置的权限页改为「允许所有照片和视频」") + locationHint
            val action = pendingPermissionAction
            pendingPermissionAction = null
            (action ?: { openBuiltInPicker() })()
        } else {
            ConvertCenter.statusText = "未授予权限，可点击「授予权限」重新申请"
        }
    }

    // 跳转系统设置后返回时刷新状态
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        locationDenied = !hasMediaLocationPermission()
        if (hasReadPermission()) {
            ConvertCenter.statusText = if (hasMediaLocationPermission())
                "已获得读取照片和视频权限"
            else "已获得媒体权限；未授予「位置」权限：转换将丢失照片 GPS 地点信息"
        }
        // 从系统设置手动授予位置权限返回 → 自动继续挂起的动作（开始/重新转换）
        val action = pendingLocationAction
        if (action != null && hasMediaLocationPermission()) {
            pendingLocationAction = null
            action()
        }
    }

    // 删除原图：系统回收站工具（createTrashRequest）结果回调——整批仅一次请求
    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        trashRequestInFlight = false
        if (result.resultCode == RESULT_OK) {
            // 记录本批被回收的原图，供应用内「恢复原图」撤销（vivo 相册不显示此类项目）
            if (lastTrashUris.isNotEmpty()) {
                addTrashedRecords(lastTrashUris)
                lastTrashUris = emptyList()
                refreshRestoreCount()
            }
            val base = "$deleteBaseStatus；原图已移入系统回收站（$pendingDeleteCount 项，" +
                "以隐藏形式保留 30 天，可在本应用「恢复原图」）"
            // 原图已删 → 名字已腾空，把中转的转换产物以原名移入相机相册（无序号）
            finalizePendingMoves(base)
        } else {
            // 用户取消删除：原图保留，同名冲突成为真实冲突——中转产物不再自动落地，
            // 保留在输出目录，由用户决定（后续可用顶栏「移到相机」移动，会带序号）
            synchronized(ConvertCenter.pendingFinalize) { ConvertCenter.pendingFinalize.clear() }
            ConvertCenter.statusText = "$deleteBaseStatus；已取消删除原图，转换结果保留在输出目录 $outputRelPath"
        }
        ConvertCenter.pendingTrashUris.clear()
    }

    /**
     * 把中转目录中的转换产物以原名移入 DCIM/Camera（回收站确认删除后的落地）。
     * 原图已删时无同名冲突，文件保持原名；仍有同名则自动序号兜底。
     */
    private fun finalizePendingMoves(baseStatus: String) {
        val entries = synchronized(ConvertCenter.pendingFinalize) {
            ConvertCenter.pendingFinalize.toList()
        }
        ConvertCenter.pendingFinalize.clear()
        if (entries.isEmpty()) {
            ConvertCenter.statusText = baseStatus
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var moved = 0
            for (e in entries) {
                val okMove = MediaExport.moveUriToCamera(this@MainActivity, e.uri, e.originalName)
                if (okMove) moved++
                withContext(Dispatchers.Main) {
                    val idx = ConvertCenter.items.indexOfFirst { it.item.key == e.itemKey }
                    if (idx >= 0) {
                        val ci = ConvertCenter.items[idx]
                        ConvertCenter.items[idx] = ci.copy(
                            status = if (okMove) "完成：已以原名移入相机相册"
                                     else "完成：原名移入失败，文件保留在输出目录"
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) {
                ConvertCenter.statusText = baseStatus +
                    (if (moved > 0) "；$moved 个转换结果已以原名移入相机相册" else "")
                ConvertCenter.persistQueue(applicationContext, ConvertCenter.itemsSnapshot())
            }
        }
    }

    // 恢复原图：系统确认弹窗（createTrashRequest(uris, false)）结果回调
    private val restoreRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val n = clearTrashedRecords()
            ConvertCenter.statusText = "已恢复 $n 项原图到原位置"
        } else {
            ConvertCenter.statusText = "已取消恢复原图"
        }
    }

    // 所有文件访问权限设置页返回：刷新提示，并继续授权前挂起的动作
    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val action = pendingAfterAllFiles
        pendingAfterAllFiles = null
        if (Environment.isExternalStorageManager()) {
            ConvertCenter.statusText = "已授予所有文件访问权限"
            action?.invoke()
        } else {
            ConvertCenter.statusText = "未授予所有文件访问权限：修复文件时间与静默删除原图暂不可用"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 启动时清理上次残留的暂存产物 + MediaStore 半成品记录；
        // 恢复上次未完成的转换队列（进程被杀时可续转）
        lifecycleScope.launch(Dispatchers.IO) {
            try { File(cacheDir, "output").listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
            cleanupPendingMediaStore()
            val pending = ConvertCenter.loadQueue(applicationContext)
            if (pending > 0) {
                withContext(Dispatchers.Main) {
                    ConvertCenter.statusText = "上次转换被中断，$pending 张待转换可继续（直接点「开始转换」）"
                }
            }
        }

        mediaRepo = MediaRepo(contentResolver)
        scanner = PickerScanner(mediaRepo)
        singleLiveScanner = SingleLiveScanner(mediaRepo)

        // 恢复删除原图开关 + 刷新可恢复记录数
        deleteOriginal = getSharedPreferences("vliveconvert", MODE_PRIVATE)
            .getBoolean("delete_original", false)
        // 恢复「转换后移到相机相册」开关（默认开启）
        moveToCamera = getSharedPreferences("vliveconvert", MODE_PRIVATE)
            .getBoolean("move_to_camera", true)
        refreshRestoreCount()
        // 恢复自定义输出目录
        outputRelPath = getSharedPreferences("vliveconvert", MODE_PRIVATE)
            .getString("output_rel_path", "Pictures/VLiveConvert") ?: "Pictures/VLiveConvert"

        setContent {
            VLiveConvertTheme {
                // 前台期间批次结束、攒下待确认删除的原图时立即拉起回收站弹窗
                // （onStart 只在前后台切换时触发，用户停留前台等完成时靠这里响应；
                //   仅前台拉起——后台拉系统弹窗可能直接失败、被误判为用户取消）
                androidx.compose.runtime.LaunchedEffect(ConvertCenter.pendingTrashCount) {
                    // 仅前台（当前生命周期 ≥ RESUMED）拉起：后台拉系统弹窗可能失败、被误判取消
                    if (ConvertCenter.pendingTrashCount > 0 &&
                        !ConvertCenter.isConverting && !trashRequestInFlight &&
                        lifecycle.currentState.isAtLeast(
                            androidx.lifecycle.Lifecycle.State.RESUMED)
                    ) {
                        trashRequestInFlight = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            requestDeleteOriginals(ConvertCenter.statusText)
                        }
                    }
                }

                // 系统返回键/侧滑返回：选择器与修复时间界面返回主页，主页保持默认退出行为；
                // 转换中吞掉返回，防止误退
                BackHandler(enabled = ConvertCenter.isConverting) { /* 转换中不响应返回 */ }
                BackHandler(enabled = showStatusDetail) { showStatusDetail = false }
                BackHandler(enabled = showAbout) { showAbout = false }
                BackHandler(enabled = showSettings) { showSettings = false }
                BackHandler(enabled = showFixTime) { showFixTime = false }
                BackHandler(enabled = showPicker) { showPicker = false }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    // 目标界面：授权后带权限状态一起参与重组，权限授予时也有过渡动画
                    val targetScreen = if (!hasReadPermission()) Screen.Permission
                    else if (showAbout) Screen.About
                    else if (showSettings) Screen.Settings
                    else if (showPicker) Screen.Picker
                    else if (showFixTime) Screen.FixTime
                    else Screen.Main
                    AnimatedContent(
                        targetState = targetScreen,
                        transitionSpec = {
                            // 主页 → 选择器/修复时间：新界面自右滑入；
                            // 返回：新界面（主页）自左淡入、旧界面滑回右侧，符合系统返回方向
                            if (targetState == Screen.Main && initialState != Screen.Main) {
                                (slideInHorizontally(tween(300)) { -it / 4 } +
                                        fadeIn(tween(300))) togetherWith
                                        (slideOutHorizontally(tween(300)) { it } +
                                                fadeOut(tween(220)))
                            } else {
                                (slideInHorizontally(tween(300)) { it } +
                                        fadeIn(tween(300))) togetherWith
                                        (slideOutHorizontally(tween(300)) { -it / 4 } +
                                                fadeOut(tween(220)))
                            }
                        },
                        label = "screenTransition"
                    ) { screen ->
                        when (screen) {
                            Screen.Permission -> PermissionScreen(
                                onRequest = { requestReadPermissions(null) },
                                statusText = ConvertCenter.statusText,
                                appName = getString(R.string.app_display_name)
                            )
                            Screen.Picker -> PickerScreen(
                                albums = pickerAlbums,
                                scanner = scanner,
                                scannerScope = lifecycleScope,
                                onBack = { showPicker = false },
                                onConfirm = { picked ->
                                    showPicker = false
                                    addPickedItems(picked)
                                }
                            )
                            Screen.FixTime -> FixTimeScreen(
                                albums = fixAlbums,
                                scanner = singleLiveScanner,
                                scannerScope = lifecycleScope,
                                isFixing = isFixing,
                                progress = fixProgress,
                                statusText = fixStatus,
                                selectionReset = fixSelectionReset,
                                onBack = { showFixTime = false },
                                onFix = { selected -> startFixTimes(selected) }
                            )
                            Screen.Settings -> SettingsScreen(
                                appName = getString(R.string.app_display_name),
                                versionName = appVersionName(),
                                outputRelPath = outputRelPath,
                                moveToCamera = moveToCamera,
                                deleteOriginal = deleteOriginal,
                                crashLogCount = crashLogCount,
                                isBusy = ConvertCenter.isConverting || isMovingOutputs || isFixing,
                                onBack = { showSettings = false },
                                onEditOutputPath = {
                                    outputPathInput = outputRelPath
                                    showOutputPathDialog = true
                                },
                                onToggleMoveToCamera = { on ->
                                    moveToCamera = on
                                    getSharedPreferences("vliveconvert", MODE_PRIVATE)
                                        .edit().putBoolean("move_to_camera", on).apply()
                                },
                                onToggleDeleteOriginal = { on ->
                                    // 开启且未授予所有文件访问权限时，提示授权以去掉系统确认框
                                    if (on && !Environment.isExternalStorageManager()) {
                                        showAllFilesDialog = true
                                    }
                                    deleteOriginal = on
                                    getSharedPreferences("vliveconvert", MODE_PRIVATE)
                                        .edit().putBoolean("delete_original", on).apply()
                                },
                                onOpenFixTime = { openFixTime() },
                                onMoveOutputsToCamera = { moveOutputsToCamera() },
                                onExportCrashLogs = { exportCrashLogs() },
                                onOpenAbout = { showAbout = true }
                            )
                            Screen.About -> AboutScreen(
                                appName = getString(R.string.app_display_name),
                                versionName = appVersionName(),
                                onBack = { showAbout = false }
                            )
                            Screen.Main -> MainScreen(
                                items = ConvertCenter.items.toList(),
                                appName = getString(R.string.app_display_name),
                                statusText = ConvertCenter.statusText,
                                isConverting = ConvertCenter.isConverting,
                                progress = ConvertCenter.progress,
                                progressDetail = ConvertCenter.progressDetail,
                                pendingRestoreCount = pendingRestoreCount,
                                onRestoreOriginals = { restoreTrashedOriginals() },
                                locationMissing = locationDenied && !ConvertCenter.isConverting,
                                onGrantLocation = {
                                    locationPermissionLauncher.launch(
                                        Manifest.permission.ACCESS_MEDIA_LOCATION)
                                },
                                reconvertCount = ConvertCenter.items.count {
                                    it.done && it.lostGps && !it.failed
                                },
                                onReconvertLostGps = { reconvertLostGpsItems() },
                                onReconvertItem = { ci -> reconvertOneItem(ci) },
                                outputRelPath = outputRelPath,
                                moveToCamera = moveToCamera,
                                deleteOriginal = deleteOriginal,
                                onOpenSettings = { showSettings = true },
                                onShowStatusDetail = { showStatusDetail = true },
                                onCancelConvert = { cancelConvert() },
                                onAddMore = { openBuiltInPicker() },
                                onStartConvert = { startConvert() },
                                onClearAll = {
                                    ConvertCenter.items.clear()
                                    ConvertCenter.clearQueue(applicationContext)
                                    ConvertCenter.statusText = "已清空"
                                },
                                onRemove = { ci ->
                                    ConvertCenter.items.removeAll { it.item.key == ci.item.key }
                                    ConvertCenter.persistQueue(
                                        applicationContext, ConvertCenter.itemsSnapshot())
                                }
                            )
                        }
                    }
                }

                // 转换前的影响面预估：把可确定的事实摊开再开跑
                preflight?.let { info ->
                    if (showPreflightDialog) {
                        AlertDialog(
                            onDismissRequest = { showPreflightDialog = false },
                            title = { Text("开始转换") },
                            text = {
                                val hasLoc = hasMediaLocationPermission()
                                Column {
                                    PreflightLine("待转换", "${info.pendingCount} 张")
                                    if (info.missingSources > 0) {
                                        PreflightLine(
                                            "源文件已丢失",
                                            "${info.missingSources} 张（将转换失败）",
                                            MaterialTheme.colorScheme.error
                                        )
                                    }
                                    PreflightLine(
                                        "源文件体积",
                                        Preflight.formatSize(info.totalSourceBytes) +
                                            "（输出体积与之相当）"
                                    )
                                    PreflightLine(
                                        "输出位置",
                                        if (moveToCamera) "DCIM/Camera" else outputRelPath
                                    )
                                    if (deleteOriginal) {
                                        // 只有转换成功的条目才会进入删除流程：源文件已丢失的必然失败，
                                        // 不计入可删除数（宁可少报，也不能让「将删除」多于实际）
                                        PreflightLine(
                                            "将删除原图",
                                            "${info.pendingCount - info.missingSources} 个 .jpg" +
                                                (if (info.companionVideoCount > 0)
                                                    " + ${info.companionVideoCount} 个 .mp4"
                                                 else "") +
                                                "（可在提示的入口恢复）",
                                            MaterialTheme.colorScheme.error
                                        )
                                    } else {
                                        PreflightLine("原图", "保留")
                                    }
                                    PreflightLine(
                                        "位置信息",
                                        if (hasLoc) "将保留"
                                        else "未授予位置权限，输出将不含位置信息",
                                        if (hasLoc) null else MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = skipPreflightNext,
                                            onCheckedChange = { skipPreflightNext = it }
                                        )
                                        Text(
                                            "以后不再提示",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    showPreflightDialog = false
                                    if (skipPreflightNext) {
                                        getSharedPreferences("vliveconvert", MODE_PRIVATE)
                                            .edit().putBoolean("skip_preflight", true).apply()
                                    }
                                    ConvertCenter.start(this)
                                }) { Text("开始转换") }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { showPreflightDialog = false })
                                { Text("取消") }
                            }
                        )
                    }
                }

                // 所有文件访问权限引导（删除原图 / 修复时间功能需要时）
                if (showAllFilesDialog) {
                    AlertDialog(
                        onDismissRequest = { showAllFilesDialog = false },
                        title = { Text("需要「所有文件访问权限」") },
                        text = {
                            Text(
                                "以下功能依赖「所有文件访问权限」：\n\n" +
                                "• 修复文件时间：修改已导出文件的「修改时间」属性\n\n" +
                                "• 转换后删除原图：删除不再弹系统确认框，被删文件会进入 vivo 相册的" +
                                "「第三方删除拦截」，可在相册中恢复；未授权时删除走系统回收站" +
                                "（30 天内可在本应用恢复）\n\n" +
                                "是否前往授权？"
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                showAllFilesDialog = false
                                openAllFilesAccessSettings()
                            }) { Text("去授权") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showAllFilesDialog = false }) { Text("暂不") }
                        }
                    )
                }

                // 位置权限门禁弹窗：未授予时开始转换必丢 GPS，需用户显式确认
                if (showLocationDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showLocationDialog = false
                            pendingLocationAction = null // 关闭弹窗即取消挂起的转换
                        },
                        title = { Text("缺少「位置」权限") },
                        text = {
                            Text(
                                "未授予「位置」权限时，Android 会在本应用读取照片时实时剥离 " +
                                "GPS 位置信息（系统脱敏），转换后的照片将丢失地点信息，且事后无法找回。\n\n" +
                                "强烈建议先授予「位置」权限再开始转换。"
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                showLocationDialog = false
                                // 直接发起系统授权框。不用 rationale 预判——
                                // 「从未申请过」时 rationale 同样返回 false，
                                // 旧实现据此把首次申请错误路由到系统设置，
                                // 导致授权框永远弹不出来（死循环 → 用户被迫「仍要转换」）
                                locationPermissionLauncher.launch(
                                    Manifest.permission.ACCESS_MEDIA_LOCATION)
                            }) { Text("去授权") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = {
                                showLocationDialog = false
                                pendingLocationAction = null // 消费挂起动作（强转路径自行继续）
                                proceedConvertAfterPermissionCheck(force = true)
                            }) { Text("仍要转换") }
                        }
                    )
                }

                // 位置权限「不再询问」后的设置引导弹窗：授予后返回自动继续挂起的动作
                if (showLocationSettingsDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showLocationSettingsDialog = false
                            pendingLocationAction = null // 关闭弹窗即取消挂起动作
                        },
                        title = { Text("需要手动开启位置权限") },
                        text = {
                            Text(
                                "系统不再弹出「位置」权限授权框（可能已选「不再询问」或系统限制）。\n\n" +
                                "请到：应用详情 → 权限 → 位置，手动开启后返回本应用，" +
                                (if (pendingActionIsReconvert) "重新转换会自动继续。"
                                 else "转换会自动继续。") + "\n\n" +
                                "若权限页没有相关开关，也可尝试给本应用开启" +
                                "「所有文件访问权限」后重新转换。"
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                showLocationSettingsDialog = false
                                openAppDetailSettings()
                            }) { Text("去设置") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = {
                                showLocationSettingsDialog = false
                                val action = pendingLocationAction
                                pendingLocationAction = null
                                if (action != null && !pendingActionIsReconvert) {
                                    // 普通转换：用户明确选择无位置继续
                                    proceedConvertAfterPermissionCheck(force = true)
                                } else if (action != null) {
                                    // 重转：无权限时执行无意义，仅取消
                                    ConvertCenter.statusText =
                                        "未授予「位置」权限，已取消重新转换（授权后可再点「重新转换」）"
                                }
                            }) { Text(if (pendingActionIsReconvert) "取消" else "仍要转换") }
                        }
                    )
                }

                // 状态详情：主界面只显示单行摘要，完整文案（含 GPS 警告）在此展开
                if (showStatusDetail) {
                    AlertDialog(
                        onDismissRequest = { showStatusDetail = false },
                        title = { Text("状态详情") },
                        text = {
                            Text(
                                ConvertCenter.statusText.ifEmpty { "暂无状态信息" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            Button(onClick = { showStatusDetail = false }) { Text("知道了") }
                        }
                    )
                }

                // 输出目录修改弹窗
                if (showOutputPathDialog) {
                    AlertDialog(
                        onDismissRequest = { showOutputPathDialog = false },
                        title = { Text("输出目录") },
                        text = {
                            Column {
                                Text(
                                    "相对主存储的路径，导出的单文件实况会保存到这里；留空恢复默认。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = outputPathInput,
                                    onValueChange = { outputPathInput = it },
                                    singleLine = true,
                                    label = { Text("例如 Pictures/VLiveConvert") }
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                val raw = outputPathInput.trim()
                                if (raw.isEmpty()) {
                                    outputRelPath = "Pictures/VLiveConvert"
                                    getSharedPreferences("vliveconvert", MODE_PRIVATE)
                                        .edit().putString("output_rel_path", outputRelPath).apply()
                                    showOutputPathDialog = false
                                    ConvertCenter.statusText = "输出目录已恢复默认：$outputRelPath"
                                } else {
                                    val s = sanitizeRelPath(raw)
                                    if (s == null) {
                                        ConvertCenter.statusText =
                                            "路径无效：不能包含 \\ : * ? \" < > | 或 ..（可留空恢复默认）"
                                    } else {
                                        outputRelPath = s
                                        getSharedPreferences("vliveconvert", MODE_PRIVATE)
                                            .edit().putString("output_rel_path", s).apply()
                                        showOutputPathDialog = false
                                        ConvertCenter.statusText = "输出目录已设为：$s"
                                    }
                                }
                            }) { Text("确定") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showOutputPathDialog = false }) { Text("取消") }
                        }
                    )
                }

                // 跳转设置对话框（用户选了「不再询问」）
                if (showSettingsDialog) {
                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
                        title = { Text("需要手动授予照片和视频权限") },
                        text = {
                            Text(
                                "您之前选择了「不再询问」，系统不再弹出权限对话框。\n\n" +
                                "请前往应用详情页 → 权限 → 照片和视频，手动授予访问权限后返回本应用。\n\n" +
                                "注意：必须同时授予「照片」和「视频」权限，否则双文件实况将无法找到附带的伴生视频。"
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                showSettingsDialog = false
                                openAppDetailSettings()
                            }) { Text("去设置") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showSettingsDialog = false }) { Text("取消") }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置授予/收回位置权限后返回时刷新警示条状态
        locationDenied = !hasMediaLocationPermission()
    }

    override fun onStart() {
        super.onStart()
        crashLogCount = CrashLog.count(this)
        // 前台服务完成批次后若攒下了待确认删除的原图（未授权所有文件访问路径），
        // 在回到应用时统一拉起系统回收站确认弹窗
        val pending = synchronized(ConvertCenter.pendingTrashUris) {
            ConvertCenter.pendingTrashUris.isNotEmpty()
        }
        if (pending && !trashRequestInFlight && !ConvertCenter.isConverting) {
            trashRequestInFlight = true
            lifecycleScope.launch(Dispatchers.IO) {
                requestDeleteOriginals(ConvertCenter.statusText)
            }
        }
    }

    // ---------- 权限 ----------

    private fun requestReadPermissions(after: (() -> Unit)?) {
        pendingPermissionAction = after
        requestPermissionLauncher.launch(requiredReadPermissions())
    }

    private fun openAppDetailSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            settingsLauncher.launch(intent)
        } catch (_: Exception) {}
    }

    /** 跳转「所有文件访问权限」设置页（本应用入口；失败时回退到总开关页/应用详情） */
    private fun openAllFilesAccessSettings() {
        try {
            allFilesAccessLauncher.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            try {
                allFilesAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                openAppDetailSettings()
            }
        }
    }

    // ---------- 内置选择器 ----------

    /** 打开内置选择器：只展示双文件实况照片 */
    private fun openBuiltInPicker() {
        if (ConvertCenter.isConverting) return
        if (!hasReadPermission()) {
            requestReadPermissions { openBuiltInPicker() }
            return
        }
        scanner.newSession() // 每次进入选择器开启新会话（相册扫过即不再扫）
        ConvertCenter.statusText = "正在读取相册…"
        lifecycleScope.launch(Dispatchers.IO) {
            val albums = mediaRepo.queryAlbums()
            withContext(Dispatchers.Main) {
                pickerAlbums = albums
                showPicker = true
                ConvertCenter.statusText = if (albums.isEmpty()) "未找到相册" else ""
            }
        }
    }

    /** 选择器确认：把选中的双文件实况加入转换列表（按 id 去重） */
    private fun addPickedItems(picked: List<MediaItem>) {
        if (picked.isEmpty()) return
        var added = 0
        for (item in picked) {
            if (ConvertCenter.items.any { it.item.id == item.id }) continue
            ConvertCenter.items.add(ConvertItem(item = item))
            added++
        }
        ConvertCenter.persistQueue(applicationContext, ConvertCenter.itemsSnapshot())
        ConvertCenter.statusText = if (added > 0)
            "已添加 $added 张，共 ${ConvertCenter.items.size} 张待转换"
        else "所选照片已在列表中"
    }

    // ---------- 转换 ----------

    /**
     * 开始转换：状态与批次交由前台服务执行（切后台/旋转不中断，通知栏显示进度）。
     * 位置权限门禁：缺失时系统在读取层剥离 GPS，转换必丢地点信息且不可恢复，
     * 必须让用户知情并显式选择，而不是静默转换。
     * 用户本会话已明确选过「仍要转换」则不再重复打扰（批次汇总仍会如实报告）。
     */
    private fun startConvert() {
        if (ConvertCenter.isConverting) return
        if (ConvertCenter.items.none { !it.done && !it.failed }) {
            ConvertCenter.statusText = "没有待转换的照片"
            return
        }
        if (!hasMediaLocationPermission() && !locationForceChosen) {
            pendingLocationAction = { proceedConvertAfterPermissionCheck() }
            pendingActionIsReconvert = false
            showLocationDialog = true
            return
        }
        showPreflightThenConvert()
    }

    /** 门禁弹窗后的复查：已授权（或用户选择「仍要转换」）才继续 */
    private fun proceedConvertAfterPermissionCheck(force: Boolean = false) {
        if (force) locationForceChosen = true
        if (hasMediaLocationPermission() || force) {
            showPreflightThenConvert()
        } else {
            pendingLocationAction = { proceedConvertAfterPermissionCheck() }
            pendingActionIsReconvert = false
            showLocationDialog = true
        }
    }

    /**
     * 转换前的影响面确认：批量操作（开启「删除原图」后还带不可逆性）在开跑前
     * 把可确定的事实摊开——待转换张数、源文件是否缺失、体积、会删掉哪些文件、
     * 位置信息是否会保留。用户可在弹窗内勾选「不再提示」。
     */
    private fun showPreflightThenConvert() {
        if (getSharedPreferences("vliveconvert", MODE_PRIVATE)
                .getBoolean("skip_preflight", false)) {
            ConvertCenter.start(this)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // 全部是 stat 级操作（存在性/大小/伴生视频），开销极小
            val info = Preflight.compute(ConvertCenter.itemsSnapshot())
            withContext(Dispatchers.Main) {
                preflight = info
                showPreflightDialog = true
            }
        }
    }

    /**
     * 一键重新转换丢位置的照片（主界面「重新转换」入口，批量）：
     * 重转的唯一目的就是找回 GPS，权限是硬前提——未授予时直接弹系统授权框
     * （授权框即门禁，无需再过应用内弹窗）；拒绝则引导系统设置，授予后自动继续。
     */
    private fun reconvertLostGpsItems() {
        if (ConvertCenter.isConverting) return
        if (ConvertCenter.reconvertCandidates().isEmpty()) return
        if (!hasMediaLocationPermission()) {
            pendingLocationAction = { doReconvertLostGps() }
            pendingActionIsReconvert = true
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            return
        }
        doReconvertLostGps()
    }

    /** 单条目行内「重转」：与批量入口共用同一套权限门禁与执行逻辑 */
    private fun reconvertOneItem(ci: ConvertItem) {
        if (ConvertCenter.isConverting) return
        if (!ci.done || !ci.lostGps || ci.failed) return
        if (!hasMediaLocationPermission()) {
            pendingLocationAction = { doReconvert(listOf(ci)) }
            pendingActionIsReconvert = true
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            return
        }
        doReconvert(listOf(ci))
    }

    private fun doReconvertLostGps() = doReconvert(ConvertCenter.reconvertCandidates())

    /** 取消当前转换批次（停前台服务；已完成的条目保留结果） */
    private fun cancelConvert() {
        if (!ConvertCenter.isConverting) return
        stopService(Intent(this, ConvertService::class.java))
        // 执行中的条目复位为「待转换」：否则会永久停在忙碌态（无移除按钮、一直转圈）
        ConvertCenter.items.forEachIndexed { i, ci ->
            if (ci.status.startsWith("转换中") || ci.status.startsWith("重新转换中")) {
                ConvertCenter.items[i] = ci.copy(status = "待转换")
            }
        }
        ConvertCenter.isConverting = false
        ConvertCenter.progress = 0f
        ConvertCenter.progressDetail = ""
        ConvertCenter.statusText = "已取消转换（已完成的条目保留结果）"
        ConvertCenter.persistQueue(applicationContext, ConvertCenter.itemsSnapshot())
    }

    /**
     * 执行重转：先剔除源文件已不存在的条目（原图被删，无法重转），
     * 明确标注原因并清掉丢位置标记（不再提供无意义的重转入口），其余进入重转。
     */
    private fun doReconvert(candidates: List<ConvertItem>) {
        if (candidates.isEmpty()) {
            ConvertCenter.statusText = "没有需要重新转换的照片"
            return
        }
        val (gone, alive) = candidates.partition { !File(it.item.path).exists() }
        // 源已不在（如启用过「转换后删除」）：位置无法找回，如实标注并退出重转队列
        for (t in gone) {
            ConvertCenter.replaceItem(t, t.copy(
                status = "位置无法找回：原图已被删除（可从云端备份恢复后重新转换）",
                lostGps = false))
        }
        if (gone.isNotEmpty()) {
            ConvertCenter.persistQueue(applicationContext, ConvertCenter.itemsSnapshot())
        }
        val n = ConvertCenter.beginReconvert(this, alive)
        if (n == 0 && gone.isEmpty()) {
            ConvertCenter.statusText = "没有可重新转换的照片"
        }
    }

    /** 版本名（设置页底部展示） */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    // ---------- 修复文件时间 ----------

    /** 打开修复时间界面（需「所有文件访问权限」：未授权时引导授权，授权后自动进入） */
    private fun openFixTime() {
        if (isFixing) return
        if (!Environment.isExternalStorageManager()) {
            pendingAfterAllFiles = { openFixTime() }
            showAllFilesDialog = true
            return
        }
        showFixTime = true
        fixStatus = "正在读取相册…"
        lifecycleScope.launch(Dispatchers.IO) {
            val albums = mediaRepo.queryAlbums()
            withContext(Dispatchers.Main) {
                fixAlbums = albums
                fixStatus = if (albums.isEmpty()) "未找到相册" else ""
            }
        }
    }

    /** 批量修复所选单文件实况的「修改时间」：文件名时间 → 拍摄时间，两者都无则跳过 */
    private fun startFixTimes(targets: List<MediaItem>) {
        if (targets.isEmpty() || isFixing) return
        isFixing = true
        fixProgress = 0f
        fixStatus = ""
        lifecycleScope.launch(Dispatchers.IO) {
            var ok = 0
            var skip = 0
            val fixed = mutableListOf<MediaItem>()
            for ((idx, item) in targets.withIndex()) {
                val time = PhotoTime.parseFromName(item.name)
                    ?: (if (item.dateTaken > 0) item.dateTaken else 0L)
                if (time <= 0L) {
                    skip++
                } else {
                    try {
                        // 物理文件 mtime（应用导出的文件可直改）+ 媒体库列双写
                        val mtimeOk = try {
                            File(item.path).setLastModified(time)
                        } catch (_: Exception) {
                            false
                        }
                        contentResolver.update(
                            item.uri,
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DATE_MODIFIED, time / 1000)
                                put(MediaStore.MediaColumns.DATE_TAKEN, time)
                            },
                            null, null
                        )
                        ok++
                        fixed.add(item)
                        if (!mtimeOk) {
                            withContext(Dispatchers.Main) {
                                fixStatus = "部分文件 mtime 修改未生效（已更新媒体库时间）：${item.name}"
                            }
                        }
                    } catch (_: Exception) {
                        skip++
                    }
                }
                withContext(Dispatchers.Main) {
                    fixProgress = (idx + 1).toFloat() / targets.size
                }
            }
            withContext(Dispatchers.Main) {
                isFixing = false
                fixSelectionReset++
                // 已修复条目的「修改时间」现已等于目标时间，不应再留在修复清单里
                // （界面约定：已一致的不会出现）。不移除的话，格子上残留的「现在」时间
                // 会显示为修复前的旧值，与结果自相矛盾。
                for (item in fixed) {
                    singleLiveScanner.stateOf(item.bucketId)
                        .results.removeAll { it.id == item.id }
                }
                fixStatus = "修复完成：成功 $ok 项，跳过 $skip 项" +
                    (if (skip > 0) "（文件名中无时间信息或不可写）" else "")
            }
        }
    }

    // ---------- 转换后删除原图（系统回收站确认弹窗由 Activity 拉起） ----------

    /**
     * 把前台服务攒下的待删除原图发起系统回收站确认（IO 线程调用）：
     * 过滤失效条目后整批一次 createTrashRequest。
     */
    private suspend fun requestDeleteOriginals(baseStatus: String) {
        val all = synchronized(ConvertCenter.pendingTrashUris) {
            ConvertCenter.pendingTrashUris.distinct().toList()
        }
        ConvertCenter.pendingTrashUris.clear()
        withContext(Dispatchers.Main) { ConvertCenter.pendingTrashCount = 0 }
        if (all.isEmpty()) {
            trashRequestInFlight = false
            return
        }
        // 过滤已失效条目，避免请求抛异常（媒体库查询保持在 IO 线程）
        val valid = all.filter { uri ->
            try {
                contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { it.moveToFirst() } == true
            } catch (_: Exception) {
                false
            }
        }
        if (valid.isEmpty()) {
            trashRequestInFlight = false
            withContext(Dispatchers.Main) {
                ConvertCenter.statusText = "$baseStatus；原图删除失败（无法访问原文件）"
            }
            return
        }
        deleteBaseStatus = baseStatus
        try {
            val sender = MediaStore.createTrashRequest(contentResolver, valid, true).intentSender
            pendingDeleteCount = valid.size
            lastTrashUris = valid.map { it.toString() }
            withContext(Dispatchers.Main) {
                deleteRequestLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        } catch (e: Exception) {
            trashRequestInFlight = false
            lastTrashUris = emptyList()
            withContext(Dispatchers.Main) {
                ConvertCenter.statusText = "$baseStatus；原图移入回收站失败（${e.message}）"
            }
        }
    }

    // ---------- 原图恢复（回收站路径的删除，30 天内可在应用内撤销） ----------

    private val RESTORE_WINDOW_MS = 30L * 24 * 3600 * 1000

    private data class TrashedRecord(val uri: String, val time: Long)

    private fun trashedPrefs() = getSharedPreferences("vliveconvert", MODE_PRIVATE)

    /** 读取回收站记录（过滤超过 30 天窗口的过期条目） */
    private fun loadTrashedRecords(): List<TrashedRecord> {
        return try {
            val arr = JSONArray(trashedPrefs().getString("trashed_records", "[]"))
            val cutoff = System.currentTimeMillis() - RESTORE_WINDOW_MS
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("uri")
                val t = o.optLong("time")
                if (u.isNotEmpty() && t >= cutoff) TrashedRecord(u, t) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveTrashedRecords(list: List<TrashedRecord>) {
        try {
            val arr = JSONArray()
            list.takeLast(1000).forEach { r ->
                arr.put(JSONObject().put("uri", r.uri).put("time", r.time))
            }
            trashedPrefs().edit().putString("trashed_records", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun addTrashedRecords(uris: List<String>) {
        val current = loadTrashedRecords()
        val existing = current.map { it.uri }.toHashSet()
        val now = System.currentTimeMillis()
        saveTrashedRecords(current + uris.filter { it !in existing }.map { TrashedRecord(it, now) })
    }

    private fun clearTrashedRecords(): Int {
        val n = loadTrashedRecords().size
        saveTrashedRecords(emptyList())
        pendingRestoreCount = 0
        return n
    }

    private fun refreshRestoreCount() {
        pendingRestoreCount = loadTrashedRecords().size
    }

    /**
     * 应用内恢复原图：把回收站中的记录（含伴生视频）通过系统确认弹窗恢复到原位置。
     * 仅覆盖「未授权所有文件访问」的回收站删除路径；已授权的直接删除由
     * vivo 相册「第三方删除拦截」负责恢复。媒体库查询在 IO 线程，避免主线程卡顿。
     */
    private fun restoreTrashedOriginals() {
        lifecycleScope.launch(Dispatchers.IO) {
            val uris = loadTrashedRecords()
                .mapNotNull { r -> try { Uri.parse(r.uri) } catch (_: Exception) { null } }
                .filter { uri ->
                    try {
                        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                            ?.use { it.moveToFirst() } == true
                    } catch (_: Exception) {
                        false
                    }
                }
            if (uris.isEmpty()) {
                withContext(Dispatchers.Main) {
                    clearTrashedRecords()
                    ConvertCenter.statusText = "没有可恢复的原图（记录已过期或文件已被系统清理）"
                }
                return@launch
            }
            try {
                val sender = MediaStore.createTrashRequest(contentResolver, uris, false).intentSender
                withContext(Dispatchers.Main) {
                    restoreRequestLauncher.launch(IntentSenderRequest.Builder(sender).build())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    ConvertCenter.statusText = "恢复原图失败（${e.message}）"
                }
            }
        }
    }

    // ---------- 崩溃日志 ----------

    /** 把本地崩溃日志导出到 Download/VLiveConvert，便于反馈定位 */
    private fun exportCrashLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val n = CrashLog.exportToDownloads(applicationContext)
            withContext(Dispatchers.Main) {
                ConvertCenter.statusText = if (n > 0)
                    "已导出 $n 个崩溃日志到 Download/VLiveConvert（可反馈给开发者）"
                else "没有可导出的崩溃日志"
                crashLogCount = CrashLog.count(this@MainActivity)
            }
        }
    }

    /**
     * 清理上次异常退出残留的半成品导出记录（IS_PENDING=1）：
     * MediaStore 仅向所有者暴露 pending 项，故查到的都是本应用的残留；
     * 不清理会永久占用存储（相册不可见、不可访问）。
     */
    private fun cleanupPendingMediaStore() {
        try {
            val collection =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val ids = mutableListOf<Long>()
            contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.IS_PENDING}=1",
                null, null
            )?.use { c ->
                while (c.moveToNext()) ids.add(c.getLong(0))
            }
            for (id in ids) {
                try {
                    contentResolver.delete(ContentUris.withAppendedId(collection, id), null, null)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ---------- 输出目录与移动 ----------

    /** 清洗用户输入的相对路径；非法返回 null */
    private fun sanitizeRelPath(input: String): String? {
        val s = input.trim().replace('\\', '/').trim('/')
        if (s.isEmpty() || s.contains("..")) return null
        if (s.split('/').any { it.isEmpty() || it == "." }) return null
        if (Regex("""[:*?"<>|]""").containsMatchIn(s)) return null
        return s
    }

    /** 查询指定相对路径下的全部图片（按加入时间降序；兼容带/不带尾斜杠两种存储形态） */
    private fun queryImagesIn(relPath: String): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        try {
            contentResolver.query(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.SIZE
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?,?)",
                arrayOf("$relPath/", relPath),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iPath = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val iModified = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (c.moveToNext()) {
                    val path = c.getString(iPath) ?: continue
                    result.add(MediaItem(
                        id = c.getLong(iId),
                        path = path,
                        name = c.getString(iName) ?: path.substringAfterLast('/'),
                        bucketId = 0L,
                        dateTaken = c.getLong(iTaken),
                        dateModified = c.getLong(iModified),
                        size = c.getLong(iSize)
                    ))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    /**
     * 把单个输出文件移动到 DCIM/Camera（拍摄/修改时间不变）：
     * 实现在 MediaExport.moveUriToCamera（owner 原生 move 优先，失败回退流式复制）。
     */
    private fun moveOneToCamera(item: MediaItem): Boolean =
        MediaExport.moveUriToCamera(this, item.uri, item.name)

    /** 把输出目录中的全部已转换文件移动到 DCIM/Camera */
    private fun moveOutputsToCamera() {
        if (isMovingOutputs || ConvertCenter.isConverting) return
        if (outputRelPath.equals("DCIM/Camera", ignoreCase = true)) {
            ConvertCenter.statusText = "输出目录已是 DCIM/Camera，无需移动"
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isMovingOutputs = true }
            val items = queryImagesIn(outputRelPath)
            if (items.isEmpty()) {
                withContext(Dispatchers.Main) {
                    isMovingOutputs = false
                    ConvertCenter.statusText = "输出目录（$outputRelPath）中没有可移动的文件"
                }
                return@launch
            }
            var ok = 0
            var fail = 0
            for ((idx, item) in items.withIndex()) {
                if (moveOneToCamera(item)) ok++ else fail++
                withContext(Dispatchers.Main) {
                    ConvertCenter.statusText = "正在移动到 DCIM/Camera…${idx + 1}/${items.size}"
                }
            }
            withContext(Dispatchers.Main) {
                isMovingOutputs = false
                ConvertCenter.statusText = "移动完成：$ok 个文件已移到 DCIM/Camera" +
                    (if (fail > 0) "，失败 $fail 个" else "")
            }
        }
    }
}

/** 影响面预估弹窗中的一行：左侧标签，右侧值（可着色） */
@Composable
private fun PreflightLine(
    label: String,
    value: String,
    tint: androidx.compose.ui.graphics.Color? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
