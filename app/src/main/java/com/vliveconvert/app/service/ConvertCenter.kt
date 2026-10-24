package com.vliveconvert.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vliveconvert.app.ui.ConvertItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * 转换中心：进程级共享的转换状态（Compose 可观察）+ 队列落盘。
 *
 * 线程纪律（v1.0.9 起，防 ConcurrentModificationException 闪退）：
 * - [items] 等所有 Compose 状态的**写**一律在主线程（服务内用 withContext(Main)）；
 * - 任何线程要**遍历**队列，只能用 [itemsSnapshot]（主线程取出的不可变快照），
 *   严禁在 IO 线程直接迭代 [items]——曾与主线程写并发导致转换中闪退；
 * - JSON 编解码只走 [QueueJson] 纯函数（普通 List 进出）。
 */
object ConvertCenter {

    /** 本进程启动时刻：残留清理的判据必须锚定**进程**。Activity 旋转/重建不换进程，
     *  若用「Activity 实例的创建时间」当判据，重建瞬间在途的暂存产物与 pending 行
     *  会被误判成上次残留而删掉（v1.7.19 修） */
    val processStartMs: Long = System.currentTimeMillis()


    /** 转换队列（含每项状态），UI 与服务共同读写（写均发生在主线程） */
    val items = mutableStateListOf<ConvertItem>()

    var isConverting by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var progressDetail by mutableStateOf("")
    /**
     * 「转换完成」节拍：批次结束后让完成态再停留一会儿（由 Activity 用 delay 清除）。
     *
     * 动因（真机实测）：勾了「以后不再提示」后，点击到完成之间没有任何中间态，
     * 一张照片约 300ms 就转完——而底栏交叉过渡本身要 200ms，
     * 「转换中」态的存活时间短于动画时长，用户看不到动效，只感到原地一闪。
     * 这里刻意用 delay（墙钟）而不是动画时长来实现：动画时长受系统
     * `animator_duration_scale` 支配，被关掉时反馈会再次归零。
     */
    var finishedBeat by mutableStateOf(false)
    /** 应用级状态行（权限提示 / 转换进度 / 完成摘要共用） */
    var statusText by mutableStateOf("")

    /**
     * 批次结束后待 Activity 发起「移入回收站」确认的原图 URI
     * （未授予所有文件访问权限时的删除路径；系统弹窗只能由 Activity 拉起）。
     * 仅存内存：进程被杀时这些 URI 丢失，原图保持原样（宁可漏删不可误删）。
     */
    val pendingTrashUris: MutableList<Uri> =
        Collections.synchronizedList(mutableListOf<Uri>())

    /**
     * 待确认删除的原图数（pendingTrashUris 快照）。
     * 独立成 Compose 状态：批次结束、用户仍停留前台时（onStart 不会再次触发），
     * Activity 通过观察此值变化拉起回收站确认弹窗。
     */
    var pendingTrashCount by mutableIntStateOf(0)

    /**
     * 待落地条目：「移到相机相册 + 转换后删除原图」同时开启时，
     * 转换产物先导出到输出目录（中转），待原图删除确认完成后
     * 以原名 move 进 DCIM/Camera——原图先删腾名，避免 MediaStore 自动加 "(1)" 序号
     * （安全语义不变：原图删除发生在新文件完整落盘并自检通过之后）。
     */
    class FinalizeEntry(val uri: Uri, val originalName: String, val itemKey: String)

    val pendingFinalize: MutableList<FinalizeEntry> =
        Collections.synchronizedList(mutableListOf())

    private const val QUEUE_FILE = "convert_queue.json"

    /** 主线程调用：队列的不可变快照（IO 线程的遍历/持久化只能用它） */
    fun itemsSnapshot(): List<ConvertItem> = items.toList()

    /** 把快照落盘（纯 IO 操作，只接收不可变快照，绝不读 Compose 状态） */
    fun persistQueue(context: Context, snapshot: List<ConvertItem>) {
        // 原子写：writeText 是「截断+写」，两个写者（并行完成的服务 job、Activity 侧）
        // 一交错就会把文件写坏，冷启动 fromJson 解析失败 → 整个队列静默丢弃。
        // 先写临时文件再 rename（同目录 rename 是原子的），并对写者加锁串行化
        synchronized(persistLock) {
            try {
                val target = File(context.filesDir, QUEUE_FILE)
                val tmp = File(context.filesDir, "$QUEUE_FILE.tmp")
                tmp.writeText(QueueJson.toJson(snapshot))
                if (!tmp.renameTo(target)) {
                    // 极端情况下 rename 失败（如目标被占用）：退回直接写，至少不比以前差
                    target.writeText(QueueJson.toJson(snapshot))
                    tmp.delete()
                }
            } catch (_: Exception) {}
        }
    }

    private val persistLock = Any()

    /** 清空队列文件（用户「清空」时调用） */
    fun clearQueue(context: Context) {
        try { File(context.filesDir, QUEUE_FILE).delete() } catch (_: Exception) {}
    }

    /**
     * 从磁盘恢复上次未完成的队列：IO 线程读文件并解析，主线程填充 [items]。
     * @return 恢复后仍未完成的条数（0 表示无需恢复）
     */
    suspend fun loadQueue(context: Context): Int {
        val f = File(context.filesDir, QUEUE_FILE)
        if (!f.exists()) return 0
        val parsed = try { QueueJson.fromJson(f.readText()) } catch (_: Exception) { null }
        if (parsed == null) return 0
        return withContext(Dispatchers.Main) { applyRestored(parsed) }
    }

    /**
     * 把恢复出的条目并进当前队列（主线程调用），返回其中仍未完成的条数。
     *
     * 单独成函数是为了让**接线本身**可测：`loadQueue` 是 suspend + `withContext(Main)`，
     * 在 Robolectric 的 PAUSED 主线程模型下从测试里驱动它，要么和主线程互相等成死锁，
     * 要么撞上「只能在所属线程控制 looper」——那种断言测的是框架而不是产品。
     */
    fun applyRestored(parsed: List<ConvertItem>): Int {
        // 合并而不是「非空就整批跳过」：本协程排在 MediaStore 清理之后，
        // 完全可能晚于用户的第一次添加，跳过等于把上次没转完的凭据丢掉
        val missing = mergeRestored(parsed, items.toList()).drop(items.size)
        if (missing.isEmpty()) return 0
        items.addAll(missing)
        return missing.count { !it.done && !it.failed }
    }

    /** 开始转换：持久化队列快照并启动前台服务执行批次（主线程调用） */
    fun start(context: Context): Boolean {
        if (isConverting) return false
        val targets = items.filter { !it.done && !it.failed }
        if (targets.isEmpty()) {
            statusText = "没有待转换的照片"
            return false
        }
        persistQueue(context, items.toList())
        isConverting = true
        progress = 0f
        progressDetail = "已处理 0/${targets.size}"
        if (!launchConvertService(context)) return false
        return true
    }

    /**
     * 启动转换前台服务（主线程调用）。
     * startForegroundService 在受限场景（Android 12+ 后台启动限制、部分 ROM 管控）
     * 会抛 ForegroundServiceStartNotAllowedException——不接住的话 isConverting
     * 已置 true 而服务从未启动，界面永远停在「转换中」且无进度（用户视角 = 假死）。
     * 失败时回滚状态并给出可见的错误提示，绝不静默。
     */
    private fun launchConvertService(context: Context): Boolean {
        return try {
            context.startForegroundService(
                Intent(context, ConvertService::class.java).setAction(ConvertService.ACTION_START))
            true
        } catch (e: Exception) {
            isConverting = false
            progress = 0f
            progressDetail = ""
            statusText = "无法启动转换服务（${e.javaClass.simpleName}），请回到应用后重试"
            false
        }
    }

    /**
     * 重新转换丢位置的条目（主线程调用）：把 done && lostGps 的条目重置为待转换
     * 并标记 reconvert（跳过删除原图收集、输出原地覆盖旧产物），随后启动服务批次。
     * @param targets 调用方已预过滤的「源文件仍在、可重转」条目
     * @return 实际进入重转的条数（0 = 无可重转项）
     */
    fun beginReconvert(context: Context, targets: List<ConvertItem>): Int {
        if (isConverting) return 0
        if (targets.isEmpty()) return 0
        // 重置目标条目：保留 outUri（覆盖旧产物用），清 done/failed，标记重转模式
        targets.forEach { t ->
            replaceItem(t, t.copy(
                status = "重新转换中…", done = false, failed = false, reconvert = true))
        }
        persistQueue(context, items.toList())
        isConverting = true
        progress = 0f
        progressDetail = "已处理 0/${targets.size}"
        statusText = "正在重新转换 ${targets.size} 张照片以找回位置信息…"
        if (!launchConvertService(context)) {
            // 服务没起来：条目刚被改成「重新转换中…」（busy 态、无移除按钮、转圈），
            // 若不回滚会一直卡到下一次成功批次才被覆盖——恢复原条目
            targets.forEach { replaceItem(it, it) }
            return 0
        }
        return targets.size
    }

    /**
     * 系统强制中止（Android 15+ dataSync 6 小时配额，onTimeout 回调）：
     * 状态复位 + 队列落盘。队列是"进程被杀可续转"的唯一凭据，
     * 这里不落盘，用户点「开始转换」就只能从零再来
     */
    fun abortForSystemLimit(context: Context) {
        if (!isConverting) return
        isConverting = false
        progress = 0f
        progressDetail = ""
        statusText = "系统限制了长时间后台转换（Android 15+ 配额），已中止并保存队列；" +
            "回到应用点「开始转换」可从断点继续"
        persistQueue(context, itemsSnapshot())
    }

    /** 可重转条目数（已完成但丢位置的）：主界面「重新转换找回位置」入口的计数 */
    fun reconvertCandidates(): List<ConvertItem> =
        items.filter { it.done && it.lostGps && !it.failed }

    /** 按源文件 key 替换列表项（主线程调用） */
    fun replaceItem(old: ConvertItem, new: ConvertItem) {
        val idx = items.indexOfFirst { it.item.key == old.item.key }
        if (idx >= 0) items[idx] = new
    }
}

/**
 * 冷启动恢复队列与「用户已经加进去的条目」如何合并（纯函数，可 JVM 单测）。
 *
 * 规则只有一条：**只补进当前没有的 id，绝不回退用户当前条目的状态**。
 * 旧实现是 `if (items.isNotEmpty()) return 0`——恢复协程跑在 MediaStore 清理之后，
 * 用户手快先加一张，上次没转完的那批就被整批静默丢弃；队列是「进程被杀可续转」
 * 的唯一凭据，丢掉它不是体验问题而是数据连续性问题。
 */
internal fun mergeRestored(
    parsed: List<ConvertItem>,
    current: List<ConvertItem>
): List<ConvertItem> = current + parsed.filter { p -> current.none { it.item.id == p.item.id } }

/**
 * 「清空」撤销时的合并（纯函数，可 JVM 单测）：
 * 清空快照排在前（恢复原顺序），清空后新加入的条目保持在后；
 * 同一照片在两边都出现时只保留当前条目——撤销不能回退用户刚加进来的状态。
 */
internal fun mergeClearedUndo(
    cleared: List<ConvertItem>,
    current: List<ConvertItem>
): List<ConvertItem> = cleared.filter { c -> current.none { it.item.id == c.item.id } } + current
