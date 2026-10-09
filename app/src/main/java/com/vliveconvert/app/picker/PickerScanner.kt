package com.vliveconvert.app.picker

import androidx.compose.runtime.mutableStateListOf
import com.vliveconvert.app.core.VivoDual
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 选择器收录判定（全应用唯一的入口）：JPG 扩展名 + 同目录同名伴生视频（含扩展名
 * 大小写兼容）+ 非内嵌式实况 + vivo footer。
 *
 * 抽为顶层 internal 函数而非 PickerScanner 的私有方法，是为了能被单测直接调用——
 * 此前这里曾自行重复实现一遍「JPG 扩展名 + 硬拼小写 .mp4」的预筛，导致核心解析
 * 已兼容 `.MP4` 时，照片仍在选择器里被静默挡掉；且该缺陷落在私有方法内，
 * 单测无法直接覆盖。异常安全：单张判定失败不影响整批扫描。
 */
internal fun isDualLivePhotoPath(path: String): Boolean =
    try {
        VivoDual.isVivoDualFile(path)
    } catch (_: Exception) {
        false
    }

/**
 * 内置选择器扫描引擎：多线程识别「双文件实况照片」。
 *
 * 只收录 vivo 双文件实况（IMG_xxx.jpg + 同目录同名 IMG_xxx.mp4），判定链：
 * 1. JPG/JPEG 扩展名（最廉价，查询后内存过滤）
 * 2. 同目录存在同名 .mp4 且非空（File.exists，廉价）
 * 3. XMP 无内嵌动态照片标记 + JPG 尾部含 vivo livephoto footer（读文件尾部 8KB + 头部 XMP）
 *
 * 会话化扫描策略：
 * - 不主动监听媒体库变化；仅在打开内置选择器时开启新会话（newSession 清空全部状态）
 * - 每个相册一次会话内只扫一次：扫完（completed）后切走再切回不重扫
 * - 未扫完就切走：立即中断；切回时清空旧进度从头重扫
 *
 * 无磁盘持久化：扫描结果仅存内存，不产生任何本地存储。
 */
class PickerScanner(private val repo: MediaRepo) {

    class ScanState {
        /** 已识别为双文件实况的列表（Compose 状态，UI 直接观察） */
        val results = mutableStateListOf<MediaItem>()
        /** 当前相册已知全部项 */
        val known = ConcurrentHashMap<String, MediaItem>()
        /** 待扫描队列 */
        val pending = ConcurrentLinkedQueue<MediaItem>()
        /** 已完成扫描的 key 集合 */
        val scanned = ConcurrentHashMap.newKeySet<String>()
        /** 已扫描数（原子，UI 轮询显示） */
        val doneCount = AtomicInteger(0)
        @Volatile var total = 0
        @Volatile var running = false
        /** 本次会话内已完成全量扫描（关闭选择器前不再触发重扫） */
        @Volatile var completed = false
        /**
         * 本相册唯一的扫描 job：覆盖 reset → diff → workers 全流程。
         * workers 作为它的子协程启动，cancel 它即可连带取消所有扫描线程。
         */
        var job: Job? = null

        /**
         * 中断后的相册切回时使用：清空旧进度，从头重扫。
         * 注意：results 是 SnapshotStateList，clear 必须在 Main 线程执行——
         * 与主线程 LazyVerticalGrid 的迭代并发会抛 ConcurrentModificationException。
         */
        fun resetProgress() {
            results.clear()
            known.clear()
            pending.clear()
            scanned.clear()
            doneCount.set(0)
            total = 0
            // 上一次扫描被取消时不会走到收尾分支，running 会残留为 true——
            // 若不在此复位，切回后进度条会一直显示「扫描中」
            running = false
        }
    }

    private val states = ConcurrentHashMap<Long, ScanState>()
    @Volatile private var activeBucket: Long? = null

    fun stateOf(bucketId: Long): ScanState = states.getOrPut(bucketId) { ScanState() }

    /** 打开内置选择器时开启新会话：中断并清空全部相册的扫描状态 */
    fun newSession() {
        states.values.forEach { it.job?.cancel() }
        states.clear()
        activeBucket = null
    }

    /**
     * 进入相册：
     * - 已扫完（completed）→ 直接展示已有结果，不再扫描
     * - 从未扫过 / 上次中断 → 清空旧进度，从头全量扫描
     */
    fun enter(bucketId: Long, scope: CoroutineScope) {
        activeBucket = bucketId
        val st = stateOf(bucketId)
        if (st.completed) return
        st.job?.cancel()
        st.job = scope.launch(Dispatchers.IO) {
            val self = currentCoroutineContext()[Job]
            // SnapshotStateList 的 clear 必须在 Main 线程（与网格迭代并发会 CME 崩溃）
            withContext(Dispatchers.Main) { st.resetProgress() }
            if (!isActive) return@launch
            val hasWork = diffRefresh(st, bucketId)
            if (hasWork) {
                st.running = true
                // workers 作为本 job 的子协程启动，随父协程取消而终止
                val workers = (1..SCAN_THREADS).map {
                    launch { scanWorker(st) }
                }
                workers.forEach { it.join() }
            }
            // 收敛点（必须无条件到达）：空相册 / 查询无结果时若直接跳过，
            // UI 的完成轮询（每 200ms）永不终止 → 空转耗电，且进度条永久停在「扫描中」。
            // 先置 completed 再清 running，避免「已读到 running=true、随后读到 completed=true」
            // 的撕裂读把 spinner 锁死（UI 侧同时按 running && !completed 判定，双保险）。
            // 身份守卫：join() 之后到此处没有挂起点，被 cancel 的旧 job 仍可能执行到这里；
            // 若它已被新 job 取代（st.job 变了），就绝不能替新扫描写下「已完成」。
            if (st.job === self) {
                st.completed = true
                st.running = false
            }
        }
    }

    /** 离开相册：立即中断扫描（进度保留在内存，切回时由 enter 重扫） */
    fun leave(bucketId: Long) {
        states[bucketId]?.job?.cancel()
        if (activeBucket == bucketId) activeBucket = null
    }

    /** 单个扫描线程：逐项判定，扫到一张双文件实况即在 Main 线程追加显示 */
    private suspend fun scanWorker(st: ScanState) {
        while (currentCoroutineContext().isActive) {
            val item = st.pending.poll() ?: break
            if (!st.known.containsKey(item.key)) continue // 队列残留已被删除的项
            if (isDualLivePhotoPath(item.path)) {
                withContext(Dispatchers.Main) {
                    if (st.known.containsKey(item.key) &&
                        st.results.none { it.id == item.id }
                    ) {
                        st.results.add(item)
                    }
                }
            }
            st.scanned.add(item.key)
            st.doneCount.set(st.scanned.size)
        }
    }

    /**
     * 查询相册最新文件列表，填充待扫队列。
     * 返回是否有待扫项。
     */
    private fun diffRefresh(st: ScanState, bucketId: Long): Boolean {
        val current = repo.queryAlbumImages(bucketId)
        for (item in current) {
            st.known[item.key] = item
            st.pending.add(item)
        }
        st.total = current.size
        return st.pending.isNotEmpty()
    }

    companion object {
        private const val SCAN_THREADS = 4
    }
}
