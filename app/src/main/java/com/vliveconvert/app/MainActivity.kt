package com.vliveconvert.app

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import android.view.animation.DecelerateInterpolator
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withTimeout
import com.vliveconvert.app.R
import com.vliveconvert.app.convert.LocationDisplayRepair
import com.vliveconvert.app.convert.MediaExport
import com.vliveconvert.app.core.outputPathErrorMessage
import com.vliveconvert.app.core.sanitizeOutputRelPath
import com.vliveconvert.app.core.PhotoTime
import com.vliveconvert.app.core.XmpTemplate
import com.vliveconvert.app.picker.AlbumInfo
import com.vliveconvert.app.picker.ConvertedIndex
import com.vliveconvert.app.picker.MediaItem
import com.vliveconvert.app.picker.MediaRepo
import com.vliveconvert.app.picker.PickerScanner
import com.vliveconvert.app.picker.ScanCache
import com.vliveconvert.app.picker.PickerScreen
import com.vliveconvert.app.picker.SingleLiveScanner
import com.vliveconvert.app.service.BatchLog
import com.vliveconvert.app.service.BatchLogEntry
import com.vliveconvert.app.service.ConvertCenter
import com.vliveconvert.app.service.ConvertedStore
import com.vliveconvert.app.service.ConvertService
import com.vliveconvert.app.service.Preflight
import com.vliveconvert.app.service.mergeClearedUndo
import com.vliveconvert.app.ui.AboutScreen
import com.vliveconvert.app.ui.BrokenRepairScreen
import com.vliveconvert.app.ui.ConvertItem
import com.vliveconvert.app.ui.FixTimeScreen
import com.vliveconvert.app.ui.GlassDialog
import com.vliveconvert.app.ui.GlassIndeterminateBar
import com.vliveconvert.app.ui.GlassTextField
import com.vliveconvert.app.ui.LocalGlassBackdrop
import com.vliveconvert.app.ui.LocalReduceMotion
import com.vliveconvert.app.ui.MainScreen
import com.vliveconvert.app.ui.MotionPrefs
import com.vliveconvert.app.ui.PermissionScreen
import com.vliveconvert.app.ui.SettingsScreen
import com.vliveconvert.app.ui.ShareIntents
import com.vliveconvert.app.ui.ThemedSnackbarHost
import com.vliveconvert.app.ui.GlassDivider
import com.vliveconvert.app.ui.clickableNoRipple
import com.vliveconvert.app.ui.dateLabel
import com.vliveconvert.app.ui.rememberGlassHazeState
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.vliveconvert.app.ui.theme.VLiveConvertTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 主界面栈：权限页 → 主页 → 选择器 / 修复时间 / 设置 / 关于（用于转场方向判定） */
internal enum class Screen { Permission, Main, Picker, FixTime, Settings, About }

/**
 * 当前应渲染哪个界面。
 *
 * 规则只有一条：**嵌套页面必须早于它的父页面判定**。「修复文件时间」「关于」的唯一入口
 * 都在设置页里，一旦把 `showSettings` 排在它们前面，用户从设置页点进去时
 * `showSettings` 仍为真 → 界面永远停在设置页 → 点击看起来毫无反应。
 * 这个顺序错误让「修复文件时间」自 v1.7.3 起完全不可达，故把优先级显式写成了
 * 一个纯函数并由真值表用例守着——再排错会直接红，不靠肉眼 review。
 */
internal fun pickScreen(
    hasReadPermission: Boolean,
    showAbout: Boolean,
    showFixTime: Boolean,
    showPicker: Boolean,
    showSettings: Boolean
): Screen = when {
    !hasReadPermission -> Screen.Permission
    showAbout -> Screen.About
    showFixTime -> Screen.FixTime
    showPicker -> Screen.Picker
    showSettings -> Screen.Settings
    else -> Screen.Main
}

/**
 * 预测性返回的「弹哪一层」决策。优先级必须与 [pickScreen] **完全一致**——
 * 渲染在哪一层、返回就关哪一层，两者一旦分叉就是 v1.7.16 那个「按了没反应」的变体。
 * （BackNavigationTest + BackDecisionTest 用 16 种标志组合把这条钉死。）
 */
internal enum class BackDecision { CLOSE_ABOUT, CLOSE_FIXTIME, CLOSE_PICKER, CLOSE_SETTINGS }

internal fun backDecision(
    showAbout: Boolean,
    showFixTime: Boolean,
    showPicker: Boolean,
    showSettings: Boolean
): BackDecision? = when {
    showAbout -> BackDecision.CLOSE_ABOUT
    showFixTime -> BackDecision.CLOSE_FIXTIME
    showPicker -> BackDecision.CLOSE_PICKER
    showSettings -> BackDecision.CLOSE_SETTINGS
    else -> null
}

/** 弹掉这一层之后露出哪一层：**不要硬编码**——直接让 pickScreen 在弹层后的标志上算。
 *  硬编码曾经对 (picker=true, settings=true) 给出 Main，而 pickScreen 弹层后渲染的是
 *  设置页；该组合今天不可达，但 v1.7.16 的教训就是别赌「今天不可达」。 */
internal fun backRevealScreen(
    showAbout: Boolean,
    showFixTime: Boolean,
    showPicker: Boolean,
    showSettings: Boolean
): Screen? = when (backDecision(showAbout, showFixTime, showPicker, showSettings)) {
    BackDecision.CLOSE_ABOUT -> pickScreen(true, false, showFixTime, showPicker, showSettings)
    BackDecision.CLOSE_FIXTIME -> pickScreen(true, showAbout, false, showPicker, showSettings)
    BackDecision.CLOSE_PICKER -> pickScreen(true, showAbout, showFixTime, false, showSettings)
    BackDecision.CLOSE_SETTINGS -> pickScreen(true, showAbout, showFixTime, showPicker, false)
    null -> null
}

/** 预测性返回的进度插值：decelerate（缓出）。
 *  系统回程手势的 progress 是线性的，直接用会觉得"跟手但生硬"；
 *  官方 platform-samples 用 PathInterpolator(0,0,0,1) 缓出——起点跟手、收尾加速入位 */
internal val PredictiveEase = CubicBezierEasing(0f, 0f, 0f, 1f)

/**
 * 修复时间的收尾汇总。半途警告必须聚合后一次说清——此前逐项覆盖 fixStatus，
 * 只留最后一条，前 N 条用户永远看不到（v1.7.23 扫查遗留项）。
 * 点名最多 3 个，多了以「等」收尾，防止文案无限长
 */
internal fun fixTimeSummary(
    ok: Int, skip: Int, mtimeWarn: List<String>, mediaFail: List<String>
): String = buildString {
    append("修复完成：成功 $ok 项，跳过 $skip 项")
    if (skip > 0) append("（文件名中无时间信息或不可写）")
    if (mtimeWarn.isNotEmpty()) {
        append("；${mtimeWarn.size} 项媒体库时间已更新但物理 mtime 未生效：")
        append(mtimeWarn.take(3).joinToString("、"))
        if (mtimeWarn.size > 3) append(" 等")
    }
    if (mediaFail.isNotEmpty()) {
        append("；${mediaFail.size} 项媒体库更新失败（物理时间可能已改）：")
        append(mediaFail.take(3).joinToString("、"))
        if (mediaFail.size > 3) append(" 等")
    }
}

/** 一次续接动画的记账：目标（1f=弹出 / 0f=收回）、时长。仅用于有手势进度的续完/收回。 */
internal data class BackAnim(val target: Float, val durationMs: Int)

// 手势提交后的续完：从当前进度到位，要短——手已经松了
internal const val COMMIT_FINISH_MS = 120

/** 顶层页（正在退出的那个）的手势变换。
 *  官方规范的原版参数（缩 0.9、移 1/20 窗宽）在全屏页面之间**读不出来**——
 *  逐帧渲染验收时两页几乎都还是满幅，观感只是"抖了一下"（用户原话：不好看）。
 *  本版参数：缩 1→0.88、横移 1/14 窗宽、上浮 1/60 高度、圆角渐起、
 *  **尾段 25% 整页淡出**——提交瞬间页面已经透明，换层没有"消失感" */
internal data class PredictiveOutgoing(
    val scale: Float, val shiftFraction: Float, val liftFraction: Float,
    val cornerFraction: Float, val alpha: Float
)

internal fun predictiveOutgoing(progress: Float, amplitude: Float = 1f): PredictiveOutgoing {
    // amplitude 放大" eased 进度"本身（自驱动回放用它加强幅度到 1.35×），
    // 各分量在函数内自行钳制，p>1 不会把页面拉爆
    val raw = progress.coerceIn(0f, 1f)
    val p = PredictiveEase.transform(raw) * amplitude
    // 淡出门槛必须锚定**原始**进度（最后 25% 手指行程）——套在缓出后的进度上时，
    // 缓出把前段推得太靠前，手指才到 70% 页面已几乎全透明（此回归被测试抓过两次）
    val fade = ((raw - 0.75f) / 0.25f).coerceIn(0f, 1f)
    return PredictiveOutgoing(
        scale = (1f - 0.12f * p).coerceAtLeast(0.78f),
        shiftFraction = (p / 14f).coerceAtMost(0.1f),
        liftFraction = p / 60f,
        cornerFraction = p.coerceAtMost(1f),
        alpha = 1f - fade,
    )
}

/** 底层页（被揭开的返回目标）的手势变换：从 0.90 缩放、反向平移 1/16 窗宽、
 *  65% 透明度**迎上来**。第一版它完全静止——顶层动 10% 它不动，整个手势
 *  看起来就像页面轻微抖了一下；深度感来自两层**相向运动** */
internal data class PredictiveIncoming(val scale: Float, val shiftFraction: Float, val alpha: Float)

internal fun predictiveIncoming(progress: Float, amplitude: Float = 1f): PredictiveIncoming {
    val p = (PredictiveEase.transform(progress.coerceIn(0f, 1f)) * amplitude).coerceAtMost(1f)
    return PredictiveIncoming(
        scale = 0.90f + 0.10f * p,
        shiftFraction = (1f - p) / 16f,
        alpha = 0.65f + 0.35f * p,
    )
}

/**
 * 「转换完成」节拍的最短可见时长（墙钟毫秒）。
 * 用 delay 而不是动画时长：动画受系统 `animator_duration_scale` 支配，
 * 一旦被关闭，完成反馈就再次塌成「一闪」。
 */
private const val FINISHED_BEAT_MS = 450L

/**
 * 一条 `IS_PENDING=1` 的导出记录是否属于「本次启动之前」的残留。
 *
 * 冷启动清理与用户第一批转换之间没有互斥（清理在 IO 协程上跑，产物导出期间同样是
 * pending 态），判据只能是时间戳、不能是「有没有批次在跑」这类可被竞态跨越的状态。
 * 边界一律向「保留」一侧靠：同一秒内新建的、或 DATE_ADDED 读不到的都当作本次批次的，
 * 漏清只是多占一条记录，误删吃掉的是用户刚转好的照片。
 */
internal fun isStalePendingMediaRow(dateAddedSec: Long, launchedAtMs: Long): Boolean =
    dateAddedSec > 0L && dateAddedSec < launchedAtMs / 1000L

/**
 * 主界面：转换队列与进度的真实状态在 [ConvertCenter]（进程级单例），
 * 批量转换由前台服务 ConvertService 执行——界面销毁/切后台不影响转换。
 * Activity 负责权限流、界面导航、系统弹窗（回收站确认）与历史文件操作。
 */
class MainActivity : ComponentActivity() {

    /**
     * 本次进程启动时刻（ms）。冷启动清理用它区分「上次残留」与「本次正在生成的产物」：
     * 清理跑在 IO 协程上，与用户第一次点「开始转换」之间没有互斥，只认时间不认状态才安全。
     */
    // 残留判据的时间锚点必须锚定**进程**而不是本 Activity 实例（旋转/重建不换进程，
    // 实例时间会把在途产物误判成残留），见 ConvertCenter.processStartMs
    private val launchedAtMs = ConvertCenter.processStartMs

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
    // 回收站删除上下文跨重建（savedInstanceState 键）
    private val KEY_TRASH_URIS = "trash_uris"
    private val KEY_TRASH_BASE = "trash_base_status"
    private val KEY_TRASH_COUNT = "trash_count"
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
    /**
     * 必须是 Compose 状态：弹窗由 `preflight?.let { if (showPreflightDialog) … }` 把关，
     * 若它是普通字段，「状态已置上、数据还没被追踪」的那一帧会把弹窗**静默欠下**——
     * 之后没有任何东西再触发重组，直到用户点开设置/详情这类无关界面才突然补出来。
     * 这正是「冷启动第一次点了没反应，切个页面才有反应」的形状。
     */
    private var preflight: Preflight.Info? by mutableStateOf(null)
    private var skipPreflightNext by mutableStateOf(false)
    // 空间守卫拦下「开始转换」后就地红字说明（模式同 outputPathError：
    // 弹窗退场动画自愈后留在原地，用户必须先清理存储或减少所选照片）
    private var preflightSpaceError by mutableStateOf(false)
    // 关于 / 开源许可页（GPL-3.0 合规：许可证文本随包内置）
    private var showAbout by mutableStateOf(false)
    private var showBrokenRepair by mutableStateOf(false)

    // 预测性返回（Android 14+ 手势）：拖动进度实时驱动顶层页缩放/位移/圆角。
    // Animatable 而不是普通状态：拖动中 snapTo 逐帧跟手，取消时 animateTo 平滑收回
    private val backProgressAnim = Animatable(0f)
    // 手势从哪条边发起：左缘 → 顶层页向右让位（+1），右缘 → 向左（-1），0 = 无手势
    private var backEdgeSign by mutableFloatStateOf(0f)
    // 非空 = 有一段续接动画要跑。**只记账不执行**：animateTo 必须在组合内的
    // LaunchedEffect 里跑（那里才有 MonotonicFrameClock），放进 handler 块或
    // lifecycleScope，真机上卡死/崩溃（v1.7.20 事故）
    private var backAnim by mutableStateOf<BackAnim?>(null)
    // 手势提交后的弹层要**跳过** AnimatedContent 的滑动过渡（揭底层已在原地，
    // 再滑一次就是双重运动）。普通字段而不是状态：只在过渡创建的那一刻被读一次
    private var predictiveCommitArmed = false

    /**
     * 页内返回按钮：直接弹层，由换页转场演出预测性编排（与无事件手势同一条路）。
     * 转场是布局动画、必然渲染，不依赖 ROM 派发进度
     */
    private fun animateBackPop() {
        predictiveCommitArmed = false
        popTopLayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 系统确认弹窗悬着时的删除上下文必须跨重建（见 onCreate 的恢复端）
        if (lastTrashUris.isNotEmpty()) {
            outState.putStringArrayList(KEY_TRASH_URIS, ArrayList(lastTrashUris))
            outState.putString(KEY_TRASH_BASE, deleteBaseStatus)
            outState.putInt(KEY_TRASH_COUNT, pendingDeleteCount)
        }
    }

    /** 弹掉当前最上面那一层（预测性返回与普通返回共用同一份决策） */
    private fun popTopLayer() {
        when (backDecision(showAbout, showFixTime, showPicker, showSettings)) {
            BackDecision.CLOSE_ABOUT -> showAbout = false
            BackDecision.CLOSE_FIXTIME -> showFixTime = false
            BackDecision.CLOSE_PICKER -> showPicker = false
            BackDecision.CLOSE_SETTINGS -> showSettings = false
            null -> {}
        }
    }

    /**
     * 返回键关闭最上层的应用内玻璃弹窗。
     * 重建进度/空间暂停不可中断：只消费返回，不关闭弹窗。
     */
    private fun dismissTopDialog() {
        when {
            isRepairingLocation || showSpacePause -> Unit
            showPreflightDialog -> showPreflightDialog = false
            showAllFilesDialog -> showAllFilesDialog = false
            showLocationDialog -> {
                showLocationDialog = false
                pendingLocationAction = null
            }
            showLocationSettingsDialog -> {
                showLocationSettingsDialog = false
                pendingLocationAction = null
            }
            showSettingsDialog -> showSettingsDialog = false
            showStatusDetail -> showStatusDetail = false
            crashExportMessage != null -> crashExportMessage = null
            showRepairConfirm -> showRepairConfirm = false
            showOutputPathDialog -> showOutputPathDialog = false
            showAlbumSheet -> showAlbumSheet = false
        }
    }

    // 自定义输出目录（MediaStore 相对路径，默认 Pictures/VLiveConvert）
    private var outputRelPath by mutableStateOf("Pictures/VLiveConvert")
    private var showOutputPathDialog by mutableStateOf(false)
    // 选择器相册切换面板：与其它弹层一样纳入统一返回优先级
    private var showAlbumSheet by mutableStateOf(false)
    /** 首帧已绘制：品牌启动动画播完即可放手 */
    private var firstFrameReady = false
    /** 闪屏退场已开始：内容层据此同步做起入场（与淡出重叠，无空档） */
    private var contentRevealStarted = false
    // 崩溃日志导出结果的弹窗文案；null = 不弹。用弹窗而不是顶栏小字（用户反馈过看不见）
    // 返回链路诊断：最近一次手势的事件数/路径/动画结果，就地显示在设置页（真机取证用）
    private var lastBackTrace by mutableStateOf("")
    private var crashExportMessage by mutableStateOf<String?>(null)
    // 分享进成功后的确认弹窗文案（null = 不弹）
    private var sharedImportMessage by mutableStateOf<String?>(null)
    private var outputPathInput by mutableStateOf("")
    // 路径校验失败要在弹窗里就地显示；底栏状态被弹窗遮住，用户看不到
    private var outputPathError by mutableStateOf<String?>(null)
    // 批次日志（设置 → 转换记录）：计数随设置页刷新；条目在点开时加载
    private var batchLogCount by mutableIntStateOf(0)
    private var showBatchLog by mutableStateOf(false)
    private var batchLogEntries by mutableStateOf(listOf<BatchLogEntry>())
    // 输出文件批量移动到 DCIM/Camera
    private var isMovingOutputs by mutableStateOf(false)
    // 重建历史产物的媒体库记录（让相册显示位置）
    private var isRepairingLocation by mutableStateOf(false)
    private var showRepairConfirm by mutableStateOf(false)
    private var repairProgressText by mutableStateOf("")
    // 分批重建的空间守卫：可用空间不足时暂停，提示清空回收站
    private var showSpacePause by mutableStateOf(false)
    private var spacePauseText by mutableStateOf("")
    // 列表项移除的撤销条（滑动/按钮移除后 4 秒内可撤销）
    private val snackbarHostState = SnackbarHostState()
    // 跟随系统取色（莫奈）：默认开，可在设置里切回品牌蓝
    private var dynamicColor by mutableStateOf(true)

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
            val cancelled = synchronized(ConvertCenter.pendingFinalize) {
                val snapshot = ConvertCenter.pendingFinalize.toList()
                ConvertCenter.pendingFinalize.clear()
                snapshot
            }
            // 条目行内还写着「待原图删除后以原名移入相机相册」，这个事件已被用户取消、
            // 永远不会发生——把状态同步改写，别让列表挂着一条假承诺
            var rewritten = 0
            for (e in cancelled) {
                ConvertCenter.items.firstOrNull { it.item.key == e.itemKey }?.let { cur ->
                    ConvertCenter.replaceItem(
                        cur,
                        cur.copy(status = "完成：原图已保留，产物在输出目录 $outputRelPath"))
                    rewritten++
                }
            }
            if (rewritten > 0) {
                ConvertCenter.persistQueue(applicationContext, ConvertCenter.itemsSnapshot())
            }
            ConvertCenter.statusText =
                "$deleteBaseStatus；已取消删除原图，转换结果保留在输出目录 $outputRelPath"
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
        // 品牌启动动画（AVD 约 700ms）此前被首帧就绪立刻掐断，看起来像闪一下；
        // 「首帧就绪 + 最短停留 720ms」双条件让它完整播完，再统一淡出交给内容
        val launchAt = SystemClock.uptimeMillis()
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition {
            !firstFrameReady || SystemClock.uptimeMillis() - launchAt < 720L
        }
        splash.setOnExitAnimationListener { provider ->
            // 内容层同时做起入场：淡出与淡入重叠，交棒才是「丝滑」而不是先切后动
            contentRevealStarted = true
            provider.view.animate()
                .alpha(0f)
                .scaleX(1.06f).scaleY(1.06f)
                .setDuration(380L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    provider.remove()
                    // 闪屏退场会把窗口图标明暗重置回白色；交棒完成后按主题补一次
                    applySystemBarAppearance()
                }
                .start()
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 回收站确认弹窗期间 Activity 重建（旋转/主题/后台回收）：删除上下文在
        // 实例字段里，不恢复的话结果回调落在新实例上 lastTrashUris 为空 →
        // 恢复记录不入库（「恢复原图」入口失效）。结果回调发生在 START，
        // onCreate 里恢复来得及
        // 分享进：从文件管理器/相册分享一对同名 .jpg+.mp4 直接入列。
        // 旋转重建会带着同一个 intent 再走一遍 onCreate——用 savedInstanceState 挡住重复入列
        if (savedInstanceState == null) {
            handleSharedPair(intent)
        }
        if (savedInstanceState != null) {
            deleteBaseStatus = savedInstanceState.getString(KEY_TRASH_BASE, "")
            pendingDeleteCount = savedInstanceState.getInt(KEY_TRASH_COUNT, 0)
            lastTrashUris = savedInstanceState.getStringArrayList(KEY_TRASH_URIS) ?: emptyList()
        }

        // 启动时清理上次残留的暂存产物 + MediaStore 半成品记录；
        // 恢复上次未完成的转换队列（进程被杀时可续转）
        lifecycleScope.launch(Dispatchers.IO) {
            cleanupStaleStagingFiles()
            cleanupPendingMediaStore()
            val pending = ConvertCenter.loadQueue(applicationContext)
            if (pending > 0) {
                withContext(Dispatchers.Main) {
                    ConvertCenter.statusText = "上次转换被中断，$pending 张待转换可继续（直接点「开始转换」）"
                }
            }
        }

        mediaRepo = MediaRepo(contentResolver)
        scanner = PickerScanner(mediaRepo, ScanCache(applicationContext))
        singleLiveScanner = SingleLiveScanner(mediaRepo)

        // 恢复删除原图开关 + 刷新可恢复记录数
        deleteOriginal = getSharedPreferences("vliveconvert", MODE_PRIVATE)
            .getBoolean("delete_original", false)
        // 恢复「转换后移到相机相册」开关（默认开启）
        moveToCamera = getSharedPreferences("vliveconvert", MODE_PRIVATE)
            .getBoolean("move_to_camera", true)
        // 恢复「跟随系统取色」开关（默认开启）
        dynamicColor = getSharedPreferences("vliveconvert", MODE_PRIVATE)
            .getBoolean("dynamic_color", true)
        refreshRestoreCount()
        // 恢复自定义输出目录
        outputRelPath = getSharedPreferences("vliveconvert", MODE_PRIVATE)
            .getString("output_rel_path", "Pictures/VLiveConvert") ?: "Pictures/VLiveConvert"

        setContent {
            MotionPrefs.load(this)
            CompositionLocalProvider(LocalReduceMotion provides MotionPrefs.effective) {
            VLiveConvertTheme(dynamicColor = dynamicColor) {
                // 系统栏图标明暗必须跟主题走：enableEdgeToEdge 只在 onCreate 判定一次，
                // 切换莫奈/品牌配色后可能残留白图标，浅色顶栏上几乎不可见。
                val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                // 全应用共享的采样状态：页面玻璃与弹窗玻璃都采同一份背景，
                // 弹窗才能模糊它背后真实的内容，而不是另开窗口糊自己
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    firstFrameReady = true
                }
                val appHazeState = rememberGlassHazeState()
                // 折射采样层：页面内容录进 GraphicsLayer，所有玻璃卡按自身位置取背景做 SDF 折射
                val appGlassBackdrop = rememberLayerBackdrop()
                CompositionLocalProvider(LocalGlassBackdrop provides appGlassBackdrop) {
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

                // 「转换完成」节拍：让 100% 的完成态至少停留 FINISHED_BEAT_MS 再退回主按钮。
                // 单张照片转换只需 ~300ms，短于底栏过渡时长，不停一拍用户只看到「闪一下」
                androidx.compose.runtime.LaunchedEffect(ConvertCenter.finishedBeat) {
                    if (ConvertCenter.finishedBeat) {
                        // 多模态：批次完成节拍与触觉同帧（成功是有意义的时刻，给一次 CONFIRM）
                        window?.decorView?.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CONFIRM)
                        kotlinx.coroutines.delay(FINISHED_BEAT_MS)
                        ConvertCenter.finishedBeat = false
                        ConvertCenter.progress = 0f
                        ConvertCenter.progressDetail = ""
                    }
                }

                // 系统返回键/侧滑返回：必须关掉「当前画面上最上面那一层」。
                //
                // v1.7.19 起用 PredictiveBackHandler 取代四个页面级 BackHandler：
                // 它把「返回决策」「手势进度动画」「弹层动作」收进同一个回调，
                // 决策经 backDecision() 与 pickScreen() 共享同一份优先级（16 种标志
                // 组合由测试钉死），不再存在两套顺序需要人工保持相反的问题。
                //
                // 曾经写错成 about→settings→fixTime→picker：从设置页进「关于与开源许可」时
                // showAbout 与 showSettings 同时为真，第一次返回关掉的是**看不见的设置页**，
                // 画面仍是关于页（用户视角「按了没反应」），第二次返回才轮到关于页、
                // 直接落回主页（用户视角「第二次才返回，而且跳过了二级菜单」）。
                //
                // 转换中吞掉返回（防误退）；主页无层可弹 → 不拦截，
                // 让系统做跨任务/回到桌面的预测性返回动画（enableOnBackInvokedCallback 已开）。
                BackHandler(enabled = ConvertCenter.isConverting) { /* 转换中不响应返回 */ }
                BackHandler(enabled = showStatusDetail) { showStatusDetail = false }
                // 弹层改为应用内浮层后，必须自己接管返回：先于页面级预测性返回判定，
                // 否则按一次返回会把底下的页面（甚至 Activity）弹掉。
                val anyDialogOpen = showPreflightDialog || showAllFilesDialog ||
                    showLocationDialog || showLocationSettingsDialog || showSettingsDialog ||
                    showStatusDetail || crashExportMessage != null || showRepairConfirm ||
                    isRepairingLocation || showSpacePause || showOutputPathDialog || showAlbumSheet
                BackHandler(enabled = anyDialogOpen) { dismissTopDialog() }
                PredictiveBackHandler(
                    enabled = hasReadPermission() && !ConvertCenter.isConverting &&
                        (showAbout || showFixTime || showPicker || showSettings) &&
                        !anyDialogOpen
                ) { progress ->
                    var sawGesture = false
                    var eventCount = 0
                    var lastProgress = 0f
                    try {
                        progress.collect { event ->
                            eventCount++
                            lastProgress = event.progress
                            if (eventCount == 1) {
                                Log.i("BackDebug", "gesture start edge=${event.swipeEdge}")
                            }
                            // 新手势进来：掐掉任何没跑完的续接动画（LaunchedEffect 随键重启），
                            // 并复位提交标记（上个提交遗留的 true 不许污染下一次过渡）
                            backAnim = null
                            predictiveCommitArmed = false
                            backEdgeSign = when (event.swipeEdge) {
                                BackEventCompat.EDGE_LEFT -> 1f
                                BackEventCompat.EDGE_RIGHT -> -1f
                                else -> backEdgeSign
                            }
                            backProgressAnim.snapTo(event.progress)
                        }
                        Log.i("BackDebug",
                            "commit: events=$eventCount lastProgress=$lastProgress " +
                                "value=${backProgressAnim.value}")
                        lastBackTrace = if (sawGesture && backProgressAnim.value > 0f)
                            "事件 $eventCount 次，末进度 $lastProgress，路径=跟手续完"
                        else "事件 $eventCount 次，路径=直接弹层+转场编排"
                        if (!sawGesture || backProgressAnim.value <= 0f) {
                            // ROM 不派发进度事件（OriginOS 等一大批国产 ROM 如此）：
                            // 拖动全程无法跟手（系统没给进度），松手后由**换页转场本身**
                            // 演出预测性编排（缩出+淡出 / 缩放就位）——转场是窗口内布局动画，
                            // 在任何 ROM 上都必然渲染，不依赖帧时钟（自驱动预动画在真机上
                            // 因系统窗口动画接管而不可见，v1.8.6 诊断确认）
                            predictiveCommitArmed = false
                            popTopLayer()
                        } else {
                            // 手势提交：只**记账**（回填续接目标），动画由组合内的
                            // LaunchedEffect 执行——从当前进度续完，跟手感
                            backAnim = BackAnim(1f, COMMIT_FINISH_MS)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // 手势取消：记一个"收回"目标后必须重抛——取消不等于「已处理返回」
                        Log.i("BackDebug", "cancelled after events=$eventCount")
                        lastBackTrace = "事件 $eventCount 次，路径=取消收回"
                        backAnim = BackAnim(0f, 200)
                        throw e
                    }
                }

                // 预测性返回的续接动画：唯一允许 animateTo 的地方（组合上下文自带帧时钟）。
                // 目标 1f = 提交：让位动画续完 → 换层（跳过滑动过渡）→ 归零复位；
                // 目标 0f = 取消：两层一起平滑收回。
                // withTimeout 兜底：提交瞬间若页面已退到后台、帧时钟不吐帧，
                // 宁可让页面直接就位，也不能把返回链路挂死（v1.7.20 卡死的形状）
                LaunchedEffect(backAnim) {
                    val anim = backAnim ?: return@LaunchedEffect
                    Log.i("BackDebug",
                        "anim start target=${anim.target} dur=${anim.durationMs}")
                    try {
                        withTimeout(anim.durationMs + 450L) {
                            // 提交续完用 tween（有界、可超时兜底）；取消收回用弹簧——
                            // "液态玻璃"的松手回弹就该有一点点液体味
                            backProgressAnim.animateTo(
                                anim.target,
                                if (anim.target == 1f) tween(anim.durationMs)
                                else spring(dampingRatio = 0.85f, stiffness = 350f))
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        backProgressAnim.snapTo(anim.target)
                        lastBackTrace = lastBackTrace + "，动画=超时兜底（帧时钟没吐帧）"
                    }
                    if (anim.target == 1f) {
                        predictiveCommitArmed = true
                        popTopLayer()
                        Log.i("BackDebug", "anim done → popped")
                        lastBackTrace = lastBackTrace + "，动画=完整播放"
                        backProgressAnim.snapTo(0f)
                    }
                    backAnim = null
                }

                // 冷启动交棒：闪屏淡出放大的同一时刻，内容从 0.985 缩放淡入到位。
                // 兜底 1.4s 自动放行——退场回调万一被 ROM 吞掉，内容也不能一直隐形
                val contentReveal = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    snapshotFlow { contentRevealStarted }
                        .filter { it }
                        .first()
                    contentReveal.animateTo(
                        1f, tween(420, easing = com.vliveconvert.app.ui.EaseOutStrong))
                }
                LaunchedEffect(Unit) {
                    delay(1400)
                    contentReveal.animateTo(
                        1f, tween(200, easing = com.vliveconvert.app.ui.EaseOutStrong))
                    contentRevealStarted = true
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = contentReveal.value
                            val sc = 0.985f + 0.015f * contentReveal.value
                            scaleX = sc; scaleY = sc
                        }
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    // 目标界面：授权后带权限状态一起参与重组，权限授予时也有过渡动画
                    val targetScreen = pickScreen(
                        hasReadPermission(),
                        showAbout,
                        showFixTime,
                        showPicker,
                        showSettings
                    )
// armed 必须等「换层重组把 transitionSpec 评估完」之后才能复位——
                // 复位太早（弹层后立刻置 false）会让短路根本不生效；挂在 targetScreen 上
                // 最可靠：换层组合时 armed 仍为 true，组合完成后再清掉，不污染后续过渡
                LaunchedEffect(targetScreen) { predictiveCommitArmed = false }

                    // 页面内容抽成可复用的 lambda：预测性返回进行时，
                    // 底层还要再画一份「返回目标页」作为被揭开的下一层
                    val renderScreen: @Composable (Screen) -> Unit = { screen ->
                        when (screen) {
                            Screen.Permission -> PermissionScreen(
                                onRequest = { requestReadPermissions(null) },
                                statusText = ConvertCenter.statusText,
                                appName = getString(R.string.app_display_name),
                                hazeState = appHazeState
                            )
                            Screen.Picker -> {
                                // 「已转换」索引：进选择器时装载一次（IO 查媒体库）；
                                // 输出位置 = 移到相机相册 ? DCIM/Camera : 自定义输出目录
                                val convertedIndex by produceState(
                                    emptyMap<String, Long>(), showPicker
                                ) {
                                    if (showPicker) {
                                        value = withContext(Dispatchers.IO) {
                                            ConvertedIndex.load(
                                                contentResolver,
                                                if (moveToCamera) "DCIM/Camera" else outputRelPath
                                            )
                                        }
                                    }
                                }
                                // 精确索引（转换成功时落盘）：与名称启发式并集使用
                                val convertedPaths by produceState(
                                    emptyMap<String, String>(), showPicker
                                ) {
                                    if (showPicker) {
                                        value = withContext(Dispatchers.IO) {
                                            ConvertedStore.load(applicationContext)
                                        }
                                    }
                                }
                                PickerScreen(
                                    albums = pickerAlbums,
                                    scanner = scanner,
                                    scannerScope = lifecycleScope,
                                    mediaRepo = mediaRepo,
                                    convertedIndex = convertedIndex,
                                    convertedPaths = convertedPaths,
                                    onBack = { animateBackPop() },
                                    onConfirm = { picked ->
                                        // 空选时留在选择器里就地提示，别把用户踹回主界面
                                        if (picked.isNotEmpty()) showPicker = false
                                        addPickedItems(picked)
                                    },
                                    showAlbumSheet = showAlbumSheet,
                                    onAlbumSheetChange = { showAlbumSheet = it },
                                    hazeState = appHazeState
                                )
                            }
                            Screen.FixTime -> FixTimeScreen(
                                albums = fixAlbums,
                                scanner = singleLiveScanner,
                                scannerScope = lifecycleScope,
                                isFixing = isFixing,
                                progress = fixProgress,
                                statusText = fixStatus,
                                selectionReset = fixSelectionReset,
                                onBack = { animateBackPop() },
                                onFix = { selected -> startFixTimes(selected) },
                                hazeState = appHazeState
                            )
                            Screen.Settings -> SettingsScreen(
                                appName = getString(R.string.app_display_name),
                                versionName = appVersionName(),
                                statusText = ConvertCenter.statusText,
                                outputRelPath = outputRelPath,
                                moveToCamera = moveToCamera,
                                deleteOriginal = deleteOriginal,
                                crashLogCount = crashLogCount,
                                batchLogCount = batchLogCount,
                                onOpenBatchLog = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val entries = BatchLog.load(applicationContext)
                                        withContext(Dispatchers.Main) {
                                            batchLogEntries = entries
                                            showBatchLog = true
                                        }
                                    }
                                },
                                isBusy = ConvertCenter.isConverting || isMovingOutputs ||
                                    isFixing || isRepairingLocation,
                                onBack = { animateBackPop() },
                                onEditOutputPath = {
                                    outputPathInput = outputRelPath
                                    outputPathError = null
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
                                queueCount = ConvertCenter.items.size,
                                dynamicColor = dynamicColor,
                                onToggleDynamicColor = { on ->
                                    dynamicColor = on
                                    getSharedPreferences("vliveconvert", MODE_PRIVATE)
                                        .edit().putBoolean("dynamic_color", on).apply()
                                },
                                reduceMotion = MotionPrefs.effective,
                                onToggleReduceMotion = { on ->
                                    MotionPrefs.set(this@MainActivity, on)
                                },
                                onOpenFixTime = { openFixTime() },
                                onOpenBrokenRepair = { showBrokenRepair = true },
                                onRepairLocationDisplay = { showRepairConfirm = true },
                                onMoveOutputsToCamera = { moveOutputsToCamera() },
                                onExportCrashLogs = { exportCrashLogs() },
                                onClearCrashLogs = { clearCrashLogs() },
                                onOpenAbout = { showAbout = true },
                                hazeState = appHazeState
                            )
                            Screen.About -> AboutScreen(
                                appName = getString(R.string.app_display_name),
                                versionName = appVersionName(),
                                onBack = { animateBackPop() },
                                onCheckUpdates = {
                                    try {
                                        startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(
                                                "https://github.com/yuting-ou/VivoLivePhoto/releases/latest")))
                                    } catch (_: Exception) {
                                        ConvertCenter.statusText = "无法打开浏览器"
                                    }
                                },
                                hazeState = appHazeState
                            )
                            Screen.Main -> MainScreen(
                                items = ConvertCenter.items.toList(),
                                appName = getString(R.string.app_display_name),
                                statusText = ConvertCenter.statusText,
                                isConverting = ConvertCenter.isConverting,
                                finishedBeat = ConvertCenter.finishedBeat,
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
                                onClearAll = { clearAllWithUndo() },
                                failedCount = ConvertCenter.items.count { it.failed },
                                onRetryFailed = {
                                    if (ConvertCenter.resetFailed() > 0) {
                                        ConvertCenter.start(this@MainActivity)
                                    }
                                },
                                onShareItem = { ci ->
                                    val uriStr = ci.outUri
                                    if (uriStr == null) {
                                        ConvertCenter.statusText = "该条目没有可分享的产物"
                                    } else try {
                                        startActivity(
                                            Intent.createChooser(
                                                ShareIntents.forLivePhoto(Uri.parse(uriStr)),
                                                "分享实况照片"))
                                    } catch (_: Exception) {
                                        ConvertCenter.statusText = "无法分享（找不到可处理的应用）"
                                    }
                                },
                                onRemove = { ci -> removeItemWithUndo(ci) },
                                hazeState = appHazeState
                            )
                        }
                    }

                    // 预测性返回进行中：底层画「返回目标页」，并**迎着**手势动起来
                    // （从 0.94 缩放、反向平移 1/28 窗宽处滑到原位）——深度感来自两层相向运动。
                    // 选择器/修复时间的父页是设置页、它们的 DisposableEffect 不会被双重触发，
                    // 因为双渲染只发生在「返回方向」，扫描页永远是顶层而不是底层
                    if (backProgressAnim.value > 0f) {
                        val reveal = backRevealScreen(showAbout, showFixTime, showPicker, showSettings)
                        if (reveal != null && reveal != targetScreen) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val incoming = predictiveIncoming(backProgressAnim.value)
                                        scaleX = incoming.scale
                                        scaleY = incoming.scale
                                        alpha = incoming.alpha
                                        // 顶层向手势发起边让位，底层从**对面**迎上来
                                        translationX = -backEdgeSign *
                                            incoming.shiftFraction * size.width
                                    }
                            ) { renderScreen(reveal) }
                        }
                    }

                    // 顶层页跟手让位：缩放 1→0.9 + 向手势发起边平移 1/20 窗宽 + 圆角渐起。
                    // 读 backProgressAnim.value 写在 graphicsLayer 里 = 延迟读，只有拖动才重绘
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = backProgressAnim.value
                                if (p > 0f) {
                                    val outgoing = predictiveOutgoing(p)
                                    scaleX = outgoing.scale
                                    scaleY = outgoing.scale
                                    alpha = outgoing.alpha
                                    translationX = backEdgeSign *
                                        outgoing.shiftFraction * size.width
                                    translationY = -outgoing.liftFraction * size.height
                                    shadowElevation = 24.dp.toPx() * outgoing.cornerFraction
                                    shape = RoundedCornerShape(32.dp * outgoing.cornerFraction)
                                    clip = true
                                }
                            }
                    ) {
                        AnimatedContent(
                            targetState = targetScreen,
                            transitionSpec = {
                                // 手势提交的弹层：揭底层已经在原地，立即换层——
                                // 若再滑一次 300ms，同一页面会在自己身上重播入场（双重运动）
                                //
                                // v1.8.3：匀速 tween → 弹簧（苹果味的"到位"感），
                                // 位移弹簧 + 短淡入淡出；稳态收敛 ~200ms
                                val move = spring<IntOffset>(
                                    dampingRatio = 0.9f, stiffness = 520f)
                                val fadeInSpec = tween<Float>(180, easing = com.vliveconvert.app.ui.EaseOutStrong)
                                val fadeOutSpec = tween<Float>(130)
                                if (predictiveCommitArmed) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else if (targetState == Screen.Main && initialState != Screen.Main) {
                                    // 返回：预测性收场编排——旧页缩出+淡出+向手势侧位移，
                                    // 新页自 0.94 缩放就位。转场是窗口内布局动画，
                                    // 任何 ROM 上都必然渲染（v1.8.6 诊断确认自驱动预动画
                                    // 在 OriginOS 上被系统窗口动画接管而不可见）
                                    (scaleIn(initialScale = 0.94f,
                                        animationSpec = tween(240, easing = PredictiveEase)) +
                                            fadeIn(tween(240))) togetherWith
                                            (scaleOut(targetScale = 0.86f,
                                                animationSpec = tween(220, easing = PredictiveEase)) +
                                                    fadeOut(tween(200)) +
                                                    slideOutHorizontally(tween(220)) { it / 5 })
                                } else {
                                    (slideInHorizontally(move) { it } +
                                            fadeIn(fadeInSpec)) togetherWith
                                            (slideOutHorizontally(move) { -it / 4 } +
                                                    fadeOut(fadeOutSpec))
                                }
                            },
                            label = "screenTransition"
                        ) { screen -> renderScreen(screen) }
                    }

                    // 移除撤销条（Gmail 式）：滑动/按钮移除后 4 秒内可撤销。
                    // bottom 抬高到底栏按钮上方——主界面底栏常驻（输出信息 + 开始转换），
                    // 贴底会被按钮压住半截
                    ThemedSnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 160.dp, start = 16.dp, end = 16.dp)
                    )
                }

                // 修复破损实况：独立入口，不走普通转换流程
                if (showBrokenRepair) {
                    BrokenRepairScreen(onBack = {
                        showBrokenRepair = false
                        // 修复批次会写日志，回到设置时计数要立即反映
                        batchLogCount = BatchLog.load(applicationContext).size
                    })
                }

                // 转换前的影响面预估：把可确定的事实摊开再开跑
                preflight?.let { info ->
                    if (showPreflightDialog) {
                        // 主线程 stat 级操作（毫秒级），与 Preflight.compute 同一纪律
                        val usableBytes = Preflight.usableBytes()
                        val spaceGuard = Preflight.spaceGuard(info.totalSourceBytes, usableBytes)
                        GlassDialog(
                            hazeState = appHazeState,
                            onDismissRequest = { showPreflightDialog = false },
                            title = "转换前确认",
                            confirmLabel = "开始转换",
                            onConfirm = {
                                if (spaceGuard == Preflight.SpaceGuard.INSUFFICIENT) {
                                    // 拒绝关闭：弹窗自愈淡回并就地显示红字（见下方 error Text）
                                    preflightSpaceError = true
                                } else {
                                    showPreflightDialog = false
                                    if (skipPreflightNext) {
                                        getSharedPreferences("vliveconvert", MODE_PRIVATE)
                                            .edit().putBoolean("skip_preflight", true).apply()
                                    }
                                    ConvertCenter.start(this)
                                }
                            },
                            dismissLabel = "取消",
                            onDismissAction = { showPreflightDialog = false }
                        ) {
                            PreflightContent(
                                info = info,
                                usableBytes = usableBytes,
                                spaceGuard = spaceGuard,
                                moveToCamera = moveToCamera,
                                outputRelPath = outputRelPath,
                                deleteOriginal = deleteOriginal,
                                hasLocationPermission = hasMediaLocationPermission(),
                                spaceError = preflightSpaceError &&
                                    spaceGuard == Preflight.SpaceGuard.INSUFFICIENT,
                                skipPreflight = skipPreflightNext,
                                onSkipPreflightChange = { skipPreflightNext = it }
                            )
                        }
                    }
                }

                // 所有文件访问权限引导（删除原图 / 修复时间功能需要时）
                if (showAllFilesDialog) {
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = { showAllFilesDialog = false },
                        title = "需要「所有文件访问权限」",
                        confirmLabel = "去授权",
                        onConfirm = {
                            showAllFilesDialog = false
                            openAllFilesAccessSettings()
                        },
                        dismissLabel = "暂不",
                        onDismissAction = { showAllFilesDialog = false }
                    ) {
                        Text(
                            "以下功能依赖「所有文件访问权限」：\n\n" +
                            "• 修复文件时间：修改已导出文件的「修改时间」属性\n\n" +
                            "• 转换后删除原图：删除不再弹系统确认框，被删文件会进入 vivo 相册的" +
                            "「第三方删除拦截」，可在相册中恢复；未授权时删除走系统回收站" +
                            "（30 天内可在本应用恢复）\n\n" +
                            "是否前往授权？"
                        )
                    }
                }

                // 位置权限门禁弹窗：未授予时开始转换必丢 GPS，需用户显式确认
                if (showLocationDialog) {
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = {
                            showLocationDialog = false
                            pendingLocationAction = null // 关闭弹窗即取消挂起的转换
                        },
                        title = "缺少「位置」权限",
                        confirmLabel = "去授权",
                        onConfirm = {
                            showLocationDialog = false
                            // 直接发起系统授权框。不用 rationale 预判——
                            // 「从未申请过」时 rationale 同样返回 false，
                            // 旧实现据此把首次申请错误路由到系统设置，
                            // 导致授权框永远弹不出来（死循环 → 用户被迫「仍要转换」）
                            locationPermissionLauncher.launch(
                                Manifest.permission.ACCESS_MEDIA_LOCATION)
                        },
                        dismissLabel = "仍要转换",
                        onDismissAction = {
                            showLocationDialog = false
                            pendingLocationAction = null // 消费挂起动作（强转路径自行继续）
                            proceedConvertAfterPermissionCheck(force = true)
                        }
                    ) {
                        Text(
                            "未授予「位置」权限时，Android 会在本应用读取照片时实时剥离 " +
                            "GPS 位置信息（系统脱敏），转换后的照片将丢失地点信息，且事后无法找回。\n\n" +
                            "强烈建议先授予「位置」权限再开始转换。"
                        )
                    }
                }

                // 位置权限「不再询问」后的设置引导弹窗：授予后返回自动继续挂起的动作
                if (showLocationSettingsDialog) {
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = {
                            showLocationSettingsDialog = false
                            pendingLocationAction = null // 关闭弹窗即取消挂起动作
                        },
                        title = "需要手动开启位置权限",
                        confirmLabel = "去设置",
                        onConfirm = {
                            showLocationSettingsDialog = false
                            openAppDetailSettings()
                        },
                        dismissLabel = if (pendingActionIsReconvert) "取消" else "仍要转换",
                        onDismissAction = {
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
                        }
                    ) {
                        Text(
                            "系统不再弹出「位置」权限授权框（可能已选「不再询问」或系统限制）。\n\n" +
                            "请到：应用详情 → 权限 → 位置，手动开启后返回本应用，" +
                            (if (pendingActionIsReconvert) "重新转换会自动继续。"
                             else "转换会自动继续。") + "\n\n" +
                            "若权限页没有相关开关，也可尝试给本应用开启" +
                            "「所有文件访问权限」后重新转换。"
                        )
                    }
                }

                // 状态详情：主界面只显示单行摘要，完整文案（含 GPS 警告）在此展开
                if (showStatusDetail) {
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = { showStatusDetail = false },
                        title = "状态详情",
                        confirmLabel = "知道了",
                        onConfirm = { showStatusDetail = false }
                    ) {
                        Text(
                            ConvertCenter.statusText.ifEmpty { "暂无状态信息" },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // 崩溃日志导出结果：弹窗（v1.7.23）。此前反馈写在顶栏小字里，
                // 用户看半天注意不到，连点 N 次导出几十份重复文件
                crashExportMessage?.let { msg ->
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = { crashExportMessage = null },
                        title = "导出崩溃日志",
                        confirmLabel = "知道了",
                        onConfirm = { crashExportMessage = null }
                    ) {
                        Text(msg)
                    }
                }

                // 位置显示重建：确认 + 进度 + 空间暂停弹窗
                if (showRepairConfirm) {
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = { showRepairConfirm = false },
                        title = "重建位置显示",
                        confirmLabel = "开始",
                        onConfirm = {
                            showRepairConfirm = false
                            rebuildLocationDisplay()
                        },
                        dismissLabel = "取消",
                        onDismissAction = { showRepairConfirm = false }
                    ) {
                        Text(
                            "将分批重建历史转换产物的媒体库记录，让 vivo 相册显示拍摄位置。\n\n" +
                                "· 照片内容与文件名不变，重建后字节逐字节一致\n" +
                                "· 旧记录会进相册「最近删除/第三方删除拦截」（可恢复）\n" +
                                "· 存储空间不足时自动暂停，清空回收站后点「继续」即可续跑\n\n" +
                                "⚠ 重要副作用：重建会把照片的「加入相册时间」重置为操作当天，" +
                                "且该时间由系统强制托管、事后无法恢复——微信等按" +
                                "「加入时间」分组的应用会把这些照片全部归入「今天」。\n" +
                                "照片的拍摄时间、EXIF、相册时间线不受影响。\n\n" +
                                "共约 4400 张，预计 3~4 批。确认开始？"
                        )
                    }
                }
                if (isRepairingLocation) {
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = {},
                        title = "重建位置显示",
                        dismissOnBack = false,
                        dismissOnOutside = false
                    ) {
                        Text(repairProgressText)
                        Spacer(Modifier.height(14.dp))
                        GlassIndeterminateBar(height = 4.dp)
                    }
                }
                if (showSpacePause) {
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = {},
                        title = "需要释放存储空间",
                        confirmLabel = "继续",
                        onConfirm = {
                            showSpacePause = false
                            rebuildLocationDisplay()
                        },
                        dismissLabel = "结束",
                        onDismissAction = { showSpacePause = false },
                        dismissOnBack = false,
                        dismissOnOutside = false
                    ) {
                        Text(spacePauseText)
                    }
                }

                // 输出目录修改弹窗
                if (showOutputPathDialog) {
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = { showOutputPathDialog = false },
                        title = "输出目录",
                        confirmLabel = "确定",
                        onConfirm = {
                            val raw = outputPathInput.trim()
                            if (raw.isEmpty()) {
                                outputPathError = null
                                outputRelPath = "Pictures/VLiveConvert"
                                getSharedPreferences("vliveconvert", MODE_PRIVATE)
                                    .edit().putString("output_rel_path", outputRelPath).apply()
                                showOutputPathDialog = false
                                ConvertCenter.statusText = "输出目录已恢复默认：$outputRelPath"
                            } else {
                                val s = sanitizeOutputRelPath(raw)
                                if (s == null) {
                                    outputPathError = outputPathErrorMessage(raw)
                                } else {
                                    outputPathError = null
                                    outputRelPath = s
                                    getSharedPreferences("vliveconvert", MODE_PRIVATE)
                                        .edit().putString("output_rel_path", s).apply()
                                    showOutputPathDialog = false
                                    ConvertCenter.statusText = "输出目录已设为：$s"
                                }
                            }
                        },
                        dismissLabel = "取消",
                        onDismissAction = { showOutputPathDialog = false }
                    ) {
                        Text(
                            "相对主存储的路径，导出的单文件实况会保存到这里；留空恢复默认。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        GlassTextField(
                            value = outputPathInput,
                            onValueChange = {
                                outputPathInput = it
                                outputPathError = outputPathErrorMessage(it)
                            },
                            placeholder = "Pictures/VLiveConvert"
                        )
                        outputPathError?.let { error ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // 批次日志：设置 → 转换记录（内容走弹窗自带的滚动，最多 50 条）
                if (showBatchLog) {
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = { showBatchLog = false },
                        title = "转换与修复记录",
                        confirmLabel = "知道了",
                        onConfirm = { showBatchLog = false }
                    ) {
                        if (batchLogEntries.isEmpty()) {
                            Text(
                                "还没有转换或修复记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column {
                                batchLogEntries.forEach { e ->
                                    BatchLogRow(
                                        e,
                                        onOpen = { uri ->
                                            showBatchLog = false
                                            try {
                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                                            } catch (_: Exception) {
                                                ConvertCenter.statusText = "无法打开产物"
                                            }
                                        }
                                    )
                                    HorizontalDivider(
                                        Modifier.padding(vertical = 8.dp),
                                        color = MaterialTheme.colorScheme.onSurface
                                            .copy(alpha = 0.08f)
                                    )
                                }
                            }
                        }
                    }
                }

                // 分享进确认：一次明确的落点反馈（底栏一行字容易被错过）
                sharedImportMessage?.let { message ->
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = { sharedImportMessage = null },
                        title = "已接收分享",
                        confirmLabel = "知道了",
                        onConfirm = { sharedImportMessage = null }
                    ) {
                        Text(message)
                    }
                }

                // 跳转设置对话框（用户选了「不再询问」）
                if (showSettingsDialog) {
                    GlassDialog(
                        hazeState = appHazeState,
                        onDismissRequest = { showSettingsDialog = false },
                        title = "需要手动授予照片和视频权限",
                        confirmLabel = "去设置",
                        onConfirm = {
                            showSettingsDialog = false
                            openAppDetailSettings()
                        },
                        dismissLabel = "取消",
                        onDismissAction = { showSettingsDialog = false }
                    ) {
                        Text(
                            "您之前选择了「不再询问」，系统不再弹出权限对话框。\n\n" +
                            "请前往应用详情页 → 权限 → 照片和视频，手动授予访问权限后返回本应用。\n\n" +
                            "注意：必须同时授予「照片」和「视频」权限，否则双文件实况将无法找到附带的伴生视频。"
                        )
                    }
                }
            }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置授予/收回位置权限后返回时刷新警示条状态
        locationDenied = !hasMediaLocationPermission()
        // 回到前台补一次系统栏图标明暗（闪屏退场/系统栏重建可能覆盖）
        applySystemBarAppearance()
    }

    /** 系统栏图标明暗必须跟主题走：浅色主题用深色图标，深色主题用浅色图标 */
    private fun applySystemBarAppearance() {
        val nightMask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val dark = (resources.configuration.uiMode and nightMask) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onStart() {
        super.onStart()
        crashLogCount = CrashLog.count(this)
        batchLogCount = BatchLog.load(applicationContext).size
        MotionPrefs.refreshSystem(this)
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

    // ---------- 分享进 ----------

    /**
     * 处理 ACTION_SEND / ACTION_SEND_MULTIPLE：恰好一对同名 .jpg+.mp4 才入列，
     * 先落地 cacheDir/shared/（转换管线按路径读取），再按缓存路径构造条目。
     * externalSource=true：源不是相册文件，「删除原图/删后移相机」语义全部停用。
     */
    private fun handleSharedPair(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().toList()
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> return
        }
        if (uris.isEmpty()) return
        val entries = uris.map { u ->
            var name: String? = null
            var mime: String? = null
            try {
                contentResolver.query(
                    u,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        mime = c.getString(1)
                    }
                }
            } catch (_: Exception) {}
            if (mime == null) mime = try { contentResolver.getType(u) } catch (_: Exception) { null }
            Triple(u, name, mime)
        }
        val pair = ShareIntents.classify(entries)
        if (pair == null) {
            ConvertCenter.statusText = "请分享一对同名 .jpg + .mp4（一次一对）"
            return
        }
        val stem = (entries.first { it.first == pair.first }.second ?: "shared")
            .substringBeforeLast('.')
        val files = ShareIntents.copyPair(this, pair.first, pair.second, stem)
        if (files == null) {
            ConvertCenter.statusText = "接收分享的照片失败（无法读取文件内容）"
            return
        }
        val img = files.first
        ConvertCenter.items.add(
            ConvertItem(
                item = MediaItem(
                    id = -System.nanoTime(),
                    path = img.absolutePath,
                    name = img.name,
                    bucketId = -1L,
                    dateTaken = 0L,
                    dateModified = img.lastModified() / 1000,
                    size = img.length()
                ),
                externalSource = true
            )
        )
        ConvertCenter.persistQueue(applicationContext, ConvertCenter.itemsSnapshot())
        ConvertCenter.statusText =
            "已加入分享的 1 对照片（来源不在相册，转换后不会删除任何原文件）"
        sharedImportMessage = "已加入：${img.name}\n\n文件来源不在相册；转换后不会删除任何原文件。"
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
        if (picked.isEmpty()) {
            // 「添加」不再置灰，所以这里必须说话，否则又变成一个点了没反应的按钮
            ConvertCenter.statusText = "先勾选要添加的照片"
            return
        }
        // 去重用 id 集合 + 一次 addAll：逐张 `items.any` 在八千张规模是 O(n²)，
        // 真机全选 8550 张后点「添加」会卡到 ANR（Input dispatching timed out）
        val existing = ConvertCenter.items.mapTo(HashSet(ConvertCenter.items.size)) { it.item.id }
        val toAdd = picked.asSequence()
            .filter { it.id !in existing }
            .distinctBy { it.id }
            .map { ConvertItem(item = it) }
            .toList()
        if (toAdd.isNotEmpty()) ConvertCenter.items.addAll(toAdd)
        val added = toAdd.size
        val snapshot = ConvertCenter.itemsSnapshot()
        ConvertCenter.statusText = if (added > 0)
            "已添加 $added 张，共 ${ConvertCenter.items.size} 张待转换"
        else "所选照片已在列表中"
        // 队列 JSON 序列化不上主线程：8550 条约 2MB，足够把输入派发拖过 5 秒
        lifecycleScope.launch(Dispatchers.IO) {
            ConvertCenter.persistQueue(applicationContext, snapshot)
        }
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
            // 这条必须说话：冷启动会恢复上次的队列，用户再选同一张会被按 id 去重，
            // 于是列表非空却没有待转换项——只说「没有待转换的照片」等于没说
            val total = ConvertCenter.items.size
            ConvertCenter.statusText = when {
                total == 0 -> "先添加要转换的照片"
                else -> "列表里 $total 张都已处理完，没有待转换的（需找回位置请点该行「重转」）"
            }
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
        // 「删除原图」开着时，「以后不再提示」一律失效：这一批是不可逆的原始文件删除，
        // 跳过确认后就成了「一次单击、零提示、直接删照片」——真机上确实这样删掉过用户的原图
        val skip = getSharedPreferences("vliveconvert", MODE_PRIVATE)
            .getBoolean("skip_preflight", false) && !deleteOriginal
        if (skip) {
            ConvertCenter.start(this)
            return
        }
        // 必须在主线程同步计算并弹窗（v1.7.9 修复「点击开始转换无反应」）：
        // 此前放在 lifecycleScope(IO) 里，经 withContext(Main) 回跳置弹窗状态——
        // 协程一旦被取消或异常（授权返回/recreate 时序），弹窗就**静默不出现**，
        // 用户视角即「点了没反应」。而 Preflight.compute 全部是 stat 级操作
        // （存在性/大小/伴生视频，毫秒级），根本不需要离开主线程
        val info = Preflight.compute(ConvertCenter.itemsSnapshot())
        preflight = info
        preflightSpaceError = false
        showPreflightDialog = true
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
        if (ConvertCenter.isConverting) {
            // 可达：行内「重转」按钮只按条目自身状态渲染，转换进行中它依然在列表上。
            // 原先这里静默 return，用户点下去就是空气
            ConvertCenter.statusText = "转换进行中，等本轮完成后再重新转换这张"
            return
        }
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
        if (!Environment.isExternalStorageManager()) {
            pendingAfterAllFiles = { openFixTime() }
            showAllFilesDialog = true
            return
        }
        showFixTime = true
        if (isFixing) {
            // 原先这里是静默 `return`：修复进度只显示在本页，从设置页再点就是「毫无反应」，
            // 用户只能理解成卡死。进页给出进度，才是这一击该有的回应
            fixStatus = "上一批仍在修复中，进度见本页；请等待完成后再操作"
            return
        }
        // 与内置选择器一致：每次进入开启新会话，清空上次的扫描结果与 completed 标记。
        // 否则同一进程内再次进入不会重新查询媒体库——新拍的实况不出现、已删除/已修复的项仍留在清单里。
        singleLiveScanner.newSession()
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
        if (isFixing) {
            fixStatus = "上一批仍在修复中，请等待完成"
            return
        }
        if (targets.isEmpty()) {
            fixStatus = "先勾选要修复的文件"
            return
        }
        isFixing = true
        fixProgress = 0f
        fixStatus = ""
        lifecycleScope.launch(Dispatchers.IO) {
            var ok = 0
            var skip = 0
            val fixed = mutableListOf<MediaItem>()
            // 半途状态必须聚合后一次说清：逐项覆盖 fixStatus 只留最后一条，
            // 用户永远看不到前 N 条警告（v1.7.23 扫查遗留项）
            val mtimeWarn = mutableListOf<String>()   // 物理时间未生效，但媒体库时间已更新
            val mediaFail = mutableListOf<String>()   // 媒体库更新失败（物理时间可能已改）
            for ((idx, item) in targets.withIndex()) {
                // 解析失败必须计入 skip 而不是抛出：协程一旦异常终止，收尾的
                // `isFixing = false` 就被跳过，整页从此静默失灵
                val time = try {
                    PhotoTime.parseFromName(item.name)
                        ?: (if (item.dateTaken > 0) item.dateTaken else 0L)
                } catch (_: Exception) {
                    0L
                }
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
                        if (!mtimeOk) mtimeWarn.add(item.name)
                    } catch (_: Exception) {
                        // 走到这里时物理 mtime 可能已改、媒体库没改——不能再混进
                        // "跳过"里装没事，要单独点名
                        skip++
                        mediaFail.add(item.name)
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
                fixStatus = fixTimeSummary(ok, skip, mtimeWarn, mediaFail)
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
    /** 清空崩溃日志（设置页入口；条数就地在设置页刷新） */
    private fun clearCrashLogs() {
        val removed = CrashLog.clear(this)
        crashLogCount = CrashLog.count(this)
        ConvertCenter.statusText = if (removed > 0) "已清空 $removed 份崩溃日志"
        else "没有可清空的崩溃日志"
    }

    private fun exportCrashLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 必须兜底：这里在 IO 协程里，未捕获异常 = 直接闪退（用户正想导出的就是崩溃日志）
            val outcome = runCatching { CrashLog.exportToDownloads(applicationContext) }
            withContext(Dispatchers.Main) {
                // 反馈用**弹窗**而不是状态栏小字——用户明确反馈过：顶上那一小块
                // 看半天注意不到，连点 N 次导出几十份重复
                crashExportMessage = outcome.fold(
                    onSuccess = { r ->
                        when {
                            !r.hasAnyLog -> "还没有崩溃日志可导出。应用闪退时会自动留一份在这里。"
                            r.exported == 0 -> "没有新日志：${r.already} 份此前已导出过，" +
                                "不重复写 Download/VLiveConvert。"
                            else -> "已导出 ${r.exported} 份新日志到 Download/VLiveConvert。" +
                                (if (r.already > 0) "（跳过已导出的 ${r.already} 份）" else "") +
                                "\n可直接发给开发者定位问题。"
                        }
                    },
                    onFailure = { e -> "导出失败（${e.javaClass.simpleName}），可尝试重启后再导。" }
                )
                crashLogCount = CrashLog.count(this@MainActivity)
            }
        }
    }

    /**
     * 清理上次残留的暂存产物。`ConvertService` 的批次就往这同一个目录里写
     * （`File(cacheDir, "output")`，见 ConvertService.kt:102），所以只删「本次启动之前」
     * 的文件，否则冷启动清理会把正在组装的产物连锅端掉。
     */
    private fun cleanupStaleStagingFiles() {
        try {
            File(cacheDir, "output").listFiles()?.forEach { f ->
                if (f.lastModified() in 1 until launchedAtMs) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 清理上次异常退出残留的半成品导出记录（IS_PENDING=1）：
     * MediaStore 仅向所有者暴露 pending 项，故查到的都是本应用的残留；
     * 不清理会永久占用存储（相册不可见、不可访问）。
     *
     * 只删「本次启动之前」加入的记录。`ConvertService` 的产物在导出校验期间同样是
     * IS_PENDING=1（`MediaExport.exportAndVerify`），而本清理跑在 onCreate 的 IO 协程上，
     * 与用户第一次点「开始转换」之间没有任何互斥——无条件删会把冷启动后第一批的成果
     * 静默吃掉（用户视角：第一次转换没结果，重试才正常）。
     * DATE_ADDED 读不到或不可判定时一律保留：漏清理只是多留一条记录，误删丢的是照片。
     */
    private fun cleanupPendingMediaStore() {
        // 两个集合都清：Images（转换导出残留）+ Downloads（崩溃日志导出残留）。
        // IS_PENDING 行只对本应用可见、却永久占存储
        try {
            cleanupPendingRows(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            cleanupPendingRows(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        } catch (_: Exception) {}
    }

    /** 清掉指定集合里早于本进程启动的 IS_PENDING 半成品行 */
    private fun cleanupPendingRows(collection: Uri) {
        try {
            val ids = mutableListOf<Long>()
            contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                "${MediaStore.MediaColumns.IS_PENDING}=1",
                null, null
            )?.use { c ->
                val iDate = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                if (iDate >= 0) {
                    while (c.moveToNext()) {
                        if (isStalePendingMediaRow(c.getLong(iDate), launchedAtMs)) ids.add(c.getLong(0))
                    }
                }
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
    /** 查询指定相对路径下的全部图片（按加入时间降序；目录匹配大小写不敏感、兼容有无尾斜杠） */
    private fun queryImagesIn(relPath: String): List<MediaItem> {
        // LIKE 通配符转义：用户自定义目录名可含 % 与 _（ESCAPE '\'）
        val escaped = relPath
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
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
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' OR " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'",
                arrayOf(escaped, "$escaped/%"),
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

    /** 从转换列表移除一项，并给出 4 秒撤销窗口（滑动与行内按钮共用） */
    /**
     * 清空待转换列表（Gmail 式撤销）：清空不是删照片，但重扫 18k 相册要十几秒，
     * 误触后没有后悔药比单个移除更痛。快照列表与落盘队列，Long 窗口内可原位恢复。
     * 转换中列表头不显示「清空」，故不存在撤销与在跑批次抢队列的情况。
     */
    private fun clearAllWithUndo() {
        val snapshot = ConvertCenter.itemsSnapshot()
        if (snapshot.isEmpty()) return
        ConvertCenter.items.clear()
        ConvertCenter.clearQueue(applicationContext)
        ConvertCenter.statusText = "已清空"
        lifecycleScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "已清空 ${snapshot.size} 张",
                actionLabel = "撤销",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed && !ConvertCenter.isConverting) {
                val merged = mergeClearedUndo(snapshot, ConvertCenter.itemsSnapshot())
                val restored = merged.size - ConvertCenter.items.size
                ConvertCenter.items.clear()
                ConvertCenter.items.addAll(merged)
                ConvertCenter.persistQueue(applicationContext, merged)
                ConvertCenter.statusText = "已恢复 $restored 张"
            }
        }
    }

    private fun removeItemWithUndo(ci: ConvertItem) {
        val index = ConvertCenter.items.indexOfFirst { it.item.key == ci.item.key }
        if (index < 0) return
        ConvertCenter.items.removeAt(index)
        ConvertCenter.persistQueue(applicationContext, ConvertCenter.itemsSnapshot())
        lifecycleScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "已移除 ${ci.item.name}",
                actionLabel = "撤销",
                withDismissAction = true,
                // Short（≈4s）太短：用户看到提示再抬手去点，条子已经在退场了。
                // 用 Long（≈10s）——Gmail 式撤销的常用窗口，够反应又不至于赖着不走
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                val at = index.coerceAtMost(ConvertCenter.items.size)
                ConvertCenter.items.add(at, ci)
                ConvertCenter.persistQueue(applicationContext, ConvertCenter.itemsSnapshot())
            }
        }
    }

    /**
     * 重建历史产物的媒体库记录，让 vivo 相册显示位置（分批执行）。
     *
     * vivo 相册只认「出生自带坐标」的记录、事后 UPDATE 坐标列被系统静默忽略，
     * 所以存量修复 = 行重建：同名/同字节/同时间戳重插一条带坐标的记录。
     * 删旧记录会让文件进「第三方删除拦截」回收站——因此按可用空间分批：
     * 低于守卫线自动暂停，提示用户清空回收站后继续（已处理名单持久化，可续跑）。
     */
    private fun rebuildLocationDisplay() {
        if (isRepairingLocation) return
        if (ConvertCenter.isConverting) {
            ConvertCenter.statusText = "转换进行中，等本轮完成后再重建位置显示"
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isRepairingLocation = true
                repairProgressText = "正在扫描相机相册…"
            }
            try {
                val processed = LocationDisplayRepair.loadProcessed(applicationContext)
                val stats = LocationDisplayRepair.rebuildAll(
                    this@MainActivity, processed,
                    onProgress = { done, total ->
                        if (done % 25 == 0 || done == total) {
                            // mutableStateOf 可从任意线程写（快照系统保证安全）
                            repairProgressText = "正在重建 $done/$total…"
                        }
                    }
                )
                LocationDisplayRepair.saveProcessed(applicationContext, processed)
                withContext(Dispatchers.Main) {
                    isRepairingLocation = false
                    if (stats.pausedForSpace) {
                        spacePauseText = "本批已重建 ${stats.rebuilt} 张（失败 ${stats.failed}，" +
                            "无坐标跳过 ${stats.noCoords}）。\n\n可用空间已低于 10GB——请到相册" +
                            "「最近删除 / 第三方删除拦截」清空回收站释放空间，然后点「继续」跑下一批。"
                        showSpacePause = true
                        ConvertCenter.statusText = "位置显示重建已暂停：请清空回收站后继续"
                    } else {
                        ConvertCenter.statusText = "位置显示重建完成：本批重建 ${stats.rebuilt} 张，" +
                            "失败 ${stats.failed}，无坐标跳过 ${stats.noCoords}" +
                            (if (stats.remaining > 0) "；剩余 ${stats.remaining} 张，再次运行可继续"
                             else "；全部处理完毕")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isRepairingLocation = false
                    ConvertCenter.statusText = "重建失败：${e.message}"
                }
            }
        }
    }

    /**
     * 把单个输出文件移动到 DCIM/Camera（拍摄/修改时间不变）：
     * 实现在 MediaExport.moveUriToCamera（owner 原生 move 优先，失败回退流式复制）。
     */
    private fun moveOneToCamera(item: MediaItem): Boolean =
        MediaExport.moveUriToCamera(this, item.uri, item.name)

    /**
     * 把输出目录中的**已转换产物**移动到 DCIM/Camera。
     *
     * 只移动本工具产出的单文件实况（XMP 含 MotionPhoto 标记，与 MediaExport 的判别同源）：
     * 输出目录是用户可配置的，若被指向一个还放着其它照片的目录，全量移动会把无关照片
     * 一并挪进相机相册。跳过的数量如实回报。
     */
    private fun moveOutputsToCamera() {
        if (isMovingOutputs) {
            ConvertCenter.statusText = "正在移动输出文件，请稍候"
            return
        }
        if (ConvertCenter.isConverting) {
            ConvertCenter.statusText = "转换进行中，等本轮完成后再移动输出文件"
            return
        }
        if (outputRelPath.equals("DCIM/Camera", ignoreCase = true)) {
            ConvertCenter.statusText = "输出目录已是 DCIM/Camera，无需移动"
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isMovingOutputs = true }
            val all = queryImagesIn(outputRelPath)
            val items = all.filter { XmpTemplate.isMotionPhoto(it.path) }
            val skipped = all.size - items.size
            if (items.isEmpty()) {
                withContext(Dispatchers.Main) {
                    isMovingOutputs = false
                    ConvertCenter.statusText = if (all.isEmpty())
                        "输出目录（$outputRelPath）中没有可移动的文件"
                    else
                        "输出目录（$outputRelPath）中没有本工具转换的实况产物" +
                            "（已跳过 $skipped 个其它文件）"
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
                    (if (fail > 0) "，失败 $fail 个" else "") +
                    (if (skipped > 0) "；已跳过 $skipped 个非本工具产物" else "")
            }
        }
    }
}

/**
 * 影响面预估弹窗的内容：三段事实（本批 / 输出 / 副作用）+ 条件警示面板。
 * 说明性括号一律降级为值的次行小字，不再与事实同字号同权挤在一行。
 * internal 供截图夹具复用，保证夹具与真实弹窗永远是同一份布局。
 */
@Composable
internal fun PreflightContent(
    info: Preflight.Info,
    usableBytes: Long,
    spaceGuard: Preflight.SpaceGuard,
    moveToCamera: Boolean,
    outputRelPath: String,
    deleteOriginal: Boolean,
    hasLocationPermission: Boolean,
    spaceError: Boolean = false,
    skipPreflight: Boolean = false,
    onSkipPreflightChange: (Boolean) -> Unit = {}
) {
    Column {
        PreflightRow("待转换", "${info.pendingCount} 张")
        if (info.missingSources > 0) {
            PreflightRow(
                "源文件已丢失", "${info.missingSources} 张", "将转换失败",
                MaterialTheme.colorScheme.error
            )
        }
        PreflightRow(
            "源文件体积", Preflight.formatSize(info.totalSourceBytes), "输出体积与之相当"
        )
        PreflightDivider()
        PreflightRow(
            "可用空间",
            if (usableBytes <= 0L) "无法读取" else Preflight.formatSize(usableBytes),
            "预计需要 ${Preflight.formatSize(info.totalSourceBytes)}",
            if (spaceGuard == Preflight.SpaceGuard.INSUFFICIENT)
                MaterialTheme.colorScheme.error else null
        )
        PreflightRow("输出位置", if (moveToCamera) "DCIM/Camera" else outputRelPath)
        PreflightDivider()
        if (deleteOriginal) {
            // 只有转换成功的条目才会进入删除流程：源文件已丢失的必然失败，
            // 不计入可删除数（宁可少报，也不能让「将删除」多于实际）
            PreflightRow(
                "将删除原图",
                "${info.pendingCount - info.missingSources} 个 .jpg" +
                    (if (info.companionVideoCount > 0)
                        " + ${info.companionVideoCount} 个 .mp4"
                     else ""),
                Preflight.deleteCopy(
                    runCatching { Environment.isExternalStorageManager() }
                        .getOrDefault(false)),
                MaterialTheme.colorScheme.error
            )
        } else {
            PreflightRow("原图", "保留")
        }
        PreflightRow(
            "位置信息",
            if (hasLocationPermission) "将保留" else "不含位置信息",
            if (hasLocationPermission) null else "未授予「位置」权限",
            if (hasLocationPermission) null else MaterialTheme.colorScheme.error
        )
        // 时间线副作用披露（与「重建位置显示」同源，但用户最常走的
        // 转换路径此前从未被告知）：产物是新媒体行，date_added 由
        // 系统硬编码为行创建时刻——微信/抖音按该列分组，会把转换
        // 产物全部归到「今天」。列本身不可写，只能如实说清
        PreflightRow("加入相册时间", "转换当天", "微信等按「加入时间」分组的应用会归入当天")
        if (spaceError && spaceGuard == Preflight.SpaceGuard.INSUFFICIENT) {
            Spacer(Modifier.height(12.dp))
            PreflightWarnPanel(
                "空间不足，无法安全完成本批转换：请清理存储空间或减少所选照片后重试。"
            )
        }
        if (deleteOriginal) {
            // 不给「不再提示」这个选项：勾了它也不会生效，
            // 摆出来等于承诺一件做不到的事
            Spacer(Modifier.height(12.dp))
            PreflightWarnPanel("本批会删除原图（不可逆），因此每次都需要确认。")
        } else {
            Spacer(Modifier.height(4.dp))
            PreflightCheckRow(checked = skipPreflight) {
                onSkipPreflightChange(!skipPreflight)
            }
        }
    }
}

/** 预检弹窗分段线：玻璃发丝线，上下各 10dp 呼吸 */
@Composable
private fun PreflightDivider() {
    Box(Modifier.padding(vertical = 10.dp)) { GlassDivider() }
}

/**
 * 预检行：固定 84dp 标签列 + 值列；说明文字降级为值的次行 12sp 灰字。
 * 标签行高与值行同为 20sp，基线自然对齐；说明跟值的色相一致但降透明度。
 * 固定标签列在 ≥1.5× 字号下会把「源文件体积」挤成两行，改上下两行铺排。
 */
@Composable
internal fun PreflightRow(
    label: String,
    value: String,
    note: String? = null,
    tint: Color? = null
) {
    val labelColor = tint?.copy(alpha = 0.85f) ?: MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = tint ?: MaterialTheme.colorScheme.onSurface
    val noteColor = tint?.copy(alpha = 0.75f) ?: MaterialTheme.colorScheme.onSurfaceVariant
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = labelColor)
            Text(value, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = valueColor)
            if (note != null) {
                Text(
                    note, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = noteColor
                )
            }
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 12.sp, lineHeight = 20.sp),
            color = labelColor,
            modifier = Modifier.width(84.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(value, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = valueColor)
            if (note != null) {
                Text(
                    note,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                    color = noteColor,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

/** 预检警示面板：错误色浅底圆角容器，替代裸 bodySmall 文本（更醒目也更有秩序） */
@Composable
internal fun PreflightWarnPanel(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.error
        )
    }
}

/**
 * 预检「以后不再提示」行：20dp 玻璃圆点 + 文案，整行可点。
 * 替代 M3 Checkbox——它 48dp 触摸目标把文案顶到 48dp 起排，
 * 与上方 0dp 起排的内容不在同一条竖线上。
 */
@Composable
internal fun PreflightCheckRow(checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickableNoRipple(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PreflightCheckDot(checked)
        Spacer(Modifier.width(10.dp))
        Text(
            "以后不再提示",
            style = TextStyle(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 勾选圆点：与 CircleTriCheckbox 同一套玻璃语言（描边圆 + 主色底 + 白勾），20dp 缩尺 */
@Composable
private fun PreflightCheckDot(checked: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Box(
        Modifier
            .size(20.dp)
            .background(
                if (checked) Color.Transparent else Color.White.copy(alpha = 0.18f),
                CircleShape
            )
            .border(
                1.5.dp,
                if (checked) Color.White.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Canvas(Modifier.size(20.dp)) {
                val s = size.minDimension
                val stroke = 2.dp.toPx()
                drawCircle(primary, radius = s / 2 - stroke / 2 - 1.dp.toPx())
                drawLine(
                    color = onPrimary,
                    start = Offset(s * 0.30f, s * 0.53f),
                    end = Offset(s * 0.45f, s * 0.68f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = onPrimary,
                    start = Offset(s * 0.45f, s * 0.68f),
                    end = Offset(s * 0.72f, s * 0.34f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/** 「转换记录」弹窗中的一行批次摘要。internal 供截图夹具复用 */
@Composable
internal fun BatchLogRow(e: BatchLogEntry, onOpen: ((String) -> Unit)? = null) {
    val timeText = dateLabel(e.timeMs) + " " +
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.timeMs))
    val openable = e.firstOutUri != null && onOpen != null
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (openable) Modifier.clickableNoRipple(onClick = { onOpen!!(e.firstOutUri!!) })
                else Modifier
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                timeText,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (e.reconvert) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "重转",
                    style = TextStyle(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (e.repair) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "修复",
                    style = TextStyle(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.weight(1f))
            if (openable) {
                Text(
                    "查看产物 ›",
                    style = TextStyle(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        val parts = buildList {
            add("成功 ${e.ok}")
            if (e.fail > 0) add("失败 ${e.fail}")
            if (e.noGps > 0) add("无位置 ${e.noGps}")
            if (e.deletedOriginals > 0) add("删原图 ${e.deletedOriginals}")
            if (e.movedToCamera > 0) add("移入相机 ${e.movedToCamera}")
        }
        Text(
            parts.joinToString(" · "),
            style = TextStyle(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "输出：${e.dest}",
            style = TextStyle(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
