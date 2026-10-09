# Vivo Live Photo（yuting改进版）

vivo 双文件实况 → 单文件实况的字节级无损转换工具（Android）。

> 本仓库是 [brovast/VLiveConvert](https://github.com/brovast/VLiveConvert) 的改进版（v1.2.0），
> 基于 GPL-3.0 协议继续开源，改进内容见文末「改进版变更记录」。
>
> **APK 直达**：仓库根目录 [`VivoLivePhoto-v1.2.0-reconvert.apk`](./VivoLivePhoto-v1.2.0-reconvert.apk)
> （手机浏览器打开本仓库 → 点开该文件 → Download 即可安装）。

## 背景

vivo 相机拍的「实况」其实是两个文件：IMG_xxx.jpg + IMG_xxx.mp4。双文件在实际用起来痛点不少：

- 换机、网盘、第三方传输工具经常把 .mp4 落下，实况秒变普通照片
- 微信等聊天工具发出去实况直接失效
- 部分第三方相册 / 壁纸应用不认双文件

## 这是什么

VLiveConvert 把 vivo 双文件实况转成「单文件实况」——一个 .jpg 内嵌视频，等同于 vivo 相册「关闭实况」时自动合并的产物。转换后：

- ✅ vivo 相册仍识别为实况，按压可播放
- ✅ 字节级无损：不重编码，EXIF（拍摄参数 / 镜头 / GPS / 拍摄时间）原样保留
- ✅ 标准 Google Motion Photo 结构，支持单文件动态照片的第三方相册也能读

## 功能

- 📷 内置选择器：自动扫描相册，只列出双文件实况照片（普通实况、人像实况都支持），普通照片不掺杂
- ⚡ 批量转换，按内存动态并发
- 📥 转换后自动移入相机相册：导出文件直接写入 `DCIM/Camera`（重名自动追加序号，可关闭）
- 🕐 修改时间对齐照片时间：导出文件的「修改时间」取自照片拍摄时间（EXIF / 文件名）；历史已转换文件可在主界面「修复时间」中多选批量修正
- 🗑️ 可选「转换后删除原图」：原 .jpg + 伴生 .mp4 一并处理（可选静默删除或系统回收站）
- 📦 APK 仅 2.5MB，全程本地处理，不联网、不上传任何数据

## 使用流程

1. 首次启动授予「照片和视频」权限（视频权限必须，否则找不到伴生 MP4）
2. 内置选择器自动扫描相册，只显示双文件实况照片
3. 选择照片 → 「开始转换」→ 单文件实况输出到相册 `Pictures/VLiveConvert`（修改时间与照片时间一致）
4. 可选开启「转换后删除原图」：转换成功后删除原 .jpg 与伴生 .mp4
   - 授予「所有文件访问权限」（推荐）：删除立即执行、无确认框，被删文件进入 vivo 相册「第三方删除拦截」，可在相册恢复
   - 不授权：每次删除前系统弹「移入回收站」确认框，文件以隐藏形式保留 30 天，可在本应用「恢复原图」入口恢复
5. 同名输出由 MediaStore 自动追加序号，不会覆盖已有文件
6. 历史转换的文件修改时间不对？主界面右上角「修复时间」→ 多选 → 一键按文件名时间修正

## 已知说明

- 需要 Android 14 及以上（OriginOS 5 / 6 机型）
- 无损转换已在 X200 Ultra 的普通实况 + 人像实况真实样本上验证通过；
- 转换后能否被 vivo 相册识别为实况，欢迎反馈「机型 + 系统版本 + 结果」

## 转换原理（无损转换，不重编码）

输出结构（与 vivo 相册「关闭实况」合并产物逐字节对齐，经真机实测可被识别）：

```
[JPEG 主体 Primary + GainMap][streamdata 附加块][MP4 视频流][lpex box][convert footer]
```

关键处理：

| 步骤               | 说明                                                                                                                                                                                      |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 视频 uuid box 处理 | 保留`vivoMediaEStream`（旧机型实况标识，X200 Ultra 起已无）；剥掉 `vivoMediaExtInfo`（内嵌的源 footer 包装，与尾部 convert footer 重复会破坏识别）                                    |
| streamdata 附加块  | 源 JPG 中紧跟图像数据的 vivo 私有流，原样透传：普通实况约 114B（DEGS 流信息）；人像实况约 4MB（IAC 深度/虚化数据，丢失则相册不显示人像徽标、无法后编辑光圈/虚化）                         |
| lpex box           | 向`moov` 插入 LivePhotoExtension box（含封面帧时间戳等），并修复 stco/co64 偏移；缺 lpex 不被识别                                                                                       |
| convert footer     | 尾部追加`cameralbum!` footer（`com.vivo.gallery.file.convert=10004` 等），透传 imageTime，并合并源 JPG/MP4 footer 的全部附加字段（人像: `moduleid=portrait`、`joint.refocus` 等） |
| XMP                | Google Container（Primary/GainMap/MotionPhoto 视频项）+ VCamera 私有字段，`GCamera:MotionPhoto="1"`                                                                                     |
| 封面帧             | 由源 footer 的 imageTime（帧序号）按视频 fps 反推 PTS                                                                                                                                     |

## 双文件实况识别（内置选择器）

判定链（从严，避免误收录）：

1. `.jpg/.jpeg` 扩展名
2. 同目录存在同名 `.mp4` 且非空
3. XMP 无 `MotionPhoto`/`MicroVideo` 内嵌标记（排除 Google/OPPO/小米等单文件内嵌格式）
4. JPG 尾部能解析出含 28 字符 livephoto ID 的 `cameralbum!` footer

footer 尾部长度可变：普通实况 ID 字段 28 字符（tail 43B）；vivo X200 Ultra 人像实况
ID 字段前多 12 字节二进制（tail 55B，len2 字段语义已变）。解析器按 len2 试探、
失败回退「尾部直取到文件尾」，以末尾 FF 分隔 + magic 校验兜底。

注：X200 Ultra 双文件 MP4 已不含 `vivoMediaEStream` uuid box（旧 iQOO 格式才有）；
识别核心为 lpex box + convert footer + XMP。

## 代码结构

```
app/src/main/java/com/vliveconvert/app/
├── MainActivity.kt          # 权限流、选择器集成、转换调度、MediaStore 导出
├── core/                   # 无损核心（无 Android 依赖，可 JVM 单测）
│   ├── BinaryUtils/JpegUtil/Mp4Util/FooterUtil   # 大小端、JPEG 段、ISOBMFF box、cameralbum footer
│   ├── XmpTemplate.kt       # vivo 单文件实况 XMP 模板（逐字取自真机样本）+ XMP 定位
│   ├── LivePhotoAsset.kt    # 动态照片规范化表示
│   ├── VivoDual.kt          # 双文件识别 + 解析
│   └── VivoSingle.kt        # 单文件写出（uuid 剥离、lpex 合成、footer/XMP 拼装）
├── convert/Converter.kt     # 管线：detect → read → write
├── picker/                  # 内置选择器：MediaStore 直查 + 会话化多线程扫描（只收录双文件）
└── ui/                      # Compose 界面：主列表 / 权限引导 / 共享组件
```

## 改进版变更记录

### v1.2.0

- **修复转换后照片丢失地点信息（核心修复）**：未授予「位置」权限（`ACCESS_MEDIA_LOCATION`）时，
  Android 会在应用读取照片时由 MediaProvider/FUSE **在读取层实时剥离 GPS EXIF**——
  本工具的「字节级无损」忠实保留了脱敏后的字节，转换产物因此无位置且事后不可恢复；
  原版权限被拒后静默继续，用户完全无感知。修复后：转换前门禁弹窗说明后果、
  单独申请位置权限（不与媒体权限混批，避免 Android 14+ 照片选择器吞掉授权框）、
  「不再询问」时引导系统设置并在授予后返回自动继续
- **GPS 双通道保留**：EXIF 原样透传之外，转换时提取源 XMP 的 GPS 属性（部分机型把位置写进 XMP）
  合并进输出模板并注入 exif 命名空间，消除「固定模板整体替换源 XMP 丢位置」的隐性通道
- **一键重新转换找回位置（新功能）**：转换后逐张检测源图是否含 GPS（EXIF + XMP 双通道），
  丢位置的条目在主界面出现「重新转换」入口——点击后批量重转、**原地覆盖**旧产物
  （不产生 "(1)" 序号堆积，覆盖同样过写后 MD5 自检，失败自动回滚全新导出）；
  重转不触发删除原图；兼容旧版本转换记录（按状态文案推导）；源文件已删除的条目明确标注不可找回
- 修复首次授权死循环：旧逻辑以 `shouldShowRequestPermissionRationale` 预判路由，
  而「从未申请过」时该方法同样返回 false → 首次点「去授权」被错误送去系统设置，
  授权框永远弹不出来
- 测试：JVM 单测新增 8 项（GPS 检测六形态大小端、EXIF/XMP 端到端保留、队列新字段往返与
  旧队列兼容推导等）全绿

### v1.0.9

- **修复转换中闪退（严重）**：v1.0.7 引入的队列持久化在 IO 线程直接遍历 Compose 状态列表，
  与主线程的条目状态更新并发触发 `ConcurrentModificationException`——批量转换时几乎必崩。
  全面收口线程纪律：所有队列遍历只允许使用主线程取出的不可变快照（`itemsSnapshot()`），
  JSON 编解码抽为纯函数 `QueueJson`（普通 List 进出），并新增 5 组队列序列化回归测试锁定行为
- 测试基建：JVM 单测引入真实 `org.json`（替换 android.jar 的 stub 实现，共 16 测试全过）

### v1.0.8

- **重排「移到相机 + 删除原图」执行顺序，转换结果不再带 "(1)" 序号**：
  原先「先导出（与未删除的原图同名 → 自动加序号）再删原图」的自我冲突，
  改为「转换 → 导出中转 + 写后自检 → 删除原图腾出文件名 → 以**原名**移入 DCIM/Camera」。
  安全语义不变：原图删除发生在新文件完整落盘并自检通过之后；
  仅当 DCIM/Camera 真有同名文件（如用户手动复制过）才序号兜底。
  用户在回收站确认弹窗取消删除时，结果保留在输出目录不自动落地

### v1.0.7

- **前台服务执行转换（架构修复）**：批量转换不再挂在界面生命周期上——切后台、旋转屏幕、
  分屏都不会中断批次；通知栏实时显示进度，完成通知点击回到应用。队列落盘：进程被杀后
  重新打开应用可恢复未完成列表继续转换
- **写后自检（数据安全）**：每次导出后把入库文件完整回读一遍，按转换时记录的分段 MD5
  逐段比对（主图/深度数据/视频/footer），任何截断或损坏都会回滚该次导出并标记失败——
  自检不通过的照片绝不进入「删除原图」流程，杜绝「原图已删、新文件是坏的」事故
- **本地崩溃日志**：未捕获异常自动落盘（保留最近 10 份），主界面出现「日志」入口时
  一键导出到 `Download/VLiveConvert`，便于反馈定位；不联网不上传
- **扫描轮询终止**：相册扫描完成后停止 200ms 进度轮询，消除空转耗电
- 新增回归测试：写后自检（通过/篡改/截断/多余字节）、sniffXmp 极小文件回归

### v1.0.6

- **新功能「转换后移到相机相册」（默认开启）**：转换导出直接写入 `DCIM/Camera`，
  与相机拍摄的照片同目录；重名时自动追加 `(n)` 序号（避免与还在原位的源双文件同名冲突）。
  开关持久化，关闭后回到自定义输出目录行为；主界面顶栏「移到相机」保留，用于移动历史文件

### v1.0.5

- 品牌统一更名「Vivo Live Photo yuting改进版」：启动器名称、应用内标题同步更新

### v1.0.4

#### 修复闪退

- **转换管线 OOM 闪退（根因修复）**：原版 4 路并发 + 全量 `readBytes` + 层层数组复制，
  批量转换人像实况时内存峰值可达数百 MB，`OutOfMemoryError` 不被 `catch (Exception)` 捕获直接闪退。现改为：
  - 单文件异常兜底改为 `catch (Throwable)`（OOM 时该项标记失败，应用不崩）；协程取消（`CancellationException`）仍正常传播
  - 并发度按应用堆大小动态决定（≥384MB 堆 2 路，否则串行），并开启 `largeHeap`
  - `VivoSingle` 输出改为顺序流式写出（不再拼装全量 output 数组）；`Mp4Util.insertBoxIntoMoov` 单次分配完成拼装（少一份视频全量副本），输出字节与原实现完全一致
- **删除原图 / 恢复原图的主线程磁盘 IO（ANR）**：批次结束后的媒体库查询与删除、回收站恢复的过滤查询全部移至 IO 线程
- **启动时清理 MediaStore 残留**：上次异常退出遗留的 `IS_PENDING=1` 半成品记录（相册不可见却占存储）启动时自动清理

#### 修复 Bug

- **XMP 截取越界（`sniffXmp`）**：终点坐标多叠加了一个起始偏移，小文件时 `IndexOutOfBoundsException` 被吞导致 Google/小米/OPPO 内嵌单文件的排除判定失效。已修正终点计算
- **权限误报**：用户选择「选择照片」部分授权时误报「已获得视频权限」。现仅 `READ_MEDIA_VIDEO` 完整授权视为成功，否则明确提示
- **扫描期间选择器卡顿**：网格排序 + 分组快照改为按列表长度缓存，扫描进度轮询（200ms 重组）不再触发全量重排

#### 其他

- 应用更名「Vivo Live Photo xiaoyu改进版」，release 产物命名 `VivoLivePhoto.apk`
- 移除误提交的 GKE 部署 CI 模板（与 Android 项目无关）
