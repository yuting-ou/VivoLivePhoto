import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vliveconvert.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 包名与 namespace（com.vliveconvert.app）解耦：改用独立包名后，
        // 与原版 VLiveConvert / 各改进版（同包名不同签名）不再冲突——
        // 修复「软件包与现有软件包存在冲突 / 签名异常」无法覆盖安装的问题
        applicationId = "com.yutingou.yingzhongying"
        minSdk = 34
        targetSdk = 37
        versionCode = 123
        versionName = "2.8.0-test2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 只打 arm64（vivo 真机）与 x86_64（模拟器），减少 so 体积（对齐 ZLivePhoto）
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // 签名材料不再入库（2026-11-09 止血，方案 A）：私钥与密码曾随仓库公开。
    // 现在的读取顺序：app/keystore.properties（本机，gitignore）→ 环境变量（CI Secrets）。
    // 两者皆缺时不挂任何签名配置：assembleRelease 产出 app-release-unsigned.apk
    // （无法安装、CI 侧有材料自检不会发布）——绝不静默回退 debug 签名冒充正式包。
    // 签名恒定的目的不变：本机 keystore 与 CI Secret 是同一把钥匙，产物可互相覆盖升级。
    val keystoreProps = Properties().apply {
        val f = project.file("keystore.properties")
        if (f.exists()) f.inputStream().use { stream -> load(stream) }
    }
    fun signingValue(propsKey: String, envName: String): String? =
        keystoreProps.getProperty(propsKey) ?: System.getenv(envName)
    val signStoreFile = signingValue("storeFile", "VLP_STORE_FILE") ?: "release.keystore"
    val signStorePassword = signingValue("storePassword", "VLP_STORE_PASSWORD")
    val signKeyAlias = signingValue("keyAlias", "VLP_KEY_ALIAS")
    val signKeyPassword = signingValue("keyPassword", "VLP_KEY_PASSWORD")

    signingConfigs {
        if (signStorePassword != null && signKeyAlias != null && signKeyPassword != null) {
            create("release") {
                storeFile = file(signStoreFile)
                storePassword = signStorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 代码压缩/优化/混淆 + 未引用资源剔除（对齐 ZLivePhoto 的打包形式）
            optimization {
                enable = true
            }
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
            // 签名材料存在才挂（见上方说明）：缺失时产出未签名包并由 CI 材料自检拦截
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric 需要访问 res/ 资源与合并后的清单
            isIncludeAndroidResources = true
        }
    }
}

// 受限网络环境（企业代理 / CI 沙箱）下，Robolectric 首次运行需下载 android-all 运行时，
// 直连会失败。仅在显式传入 -PtestProxyHost=... -PtestProxyPort=... 时才把代理透传给
// 测试 JVM——常规本地构建不受任何影响。
tasks.withType<Test>().configureEach {
    // Robolectric + Compose 合在一个 JVM 里跑完全部用例会累积到 OOM（表现为 Recomposer 的
    // withFrameNanos 抛 OutOfMemoryError，再被 kotlinx-coroutines-test 归因成
    // 「测试开始前存在未捕获异常」——是量具问题，不是产品缺陷）。
    // 对策是「定期换 JVM」而不是「把堆开得更大」：本机内存吃紧，大堆会直接触发换页，
    // 实测 3G 堆时整套从 2 分钟劣化到 13 分钟没跑完一个测试类。
    maxHeapSize = "1024m"
    forkEvery = 3
    val proxyHost = providers.gradleProperty("testProxyHost").orNull
    val proxyPort = providers.gradleProperty("testProxyPort").orNull
    if (!proxyHost.isNullOrBlank() && !proxyPort.isNullOrBlank()) {
        listOf(
            "http.proxyHost", "https.proxyHost", "robolectric.dependency.proxy.host"
        ).forEach { systemProperty(it, proxyHost) }
        listOf(
            "http.proxyPort", "https.proxyPort", "robolectric.dependency.proxy.port"
        ).forEach { systemProperty(it, proxyPort) }
    }
}

// release 产物直接命名为 VivoLivePhoto.apk（输出到 app/build/outputs/apk/release/）
androidComponents {
    onVariants { variant ->
        if (variant.buildType == "release") {
            variant.outputs.forEach { output ->
                output.outputFileName.set("VivoLivePhoto.apk")
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // 真实背景采样模糊（液态玻璃）：GitHub chrisbanes/haze，Apache-2.0。
    // 1.6.10 用 Kotlin 2.1/Compose 1.8 构建，与本项目 Kotlin 2.2.10 兼容
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    // 液态玻璃折射/色散/高光：GitHub Kyant0/AndroidLiquidGlass，Apache-2.0。
    // 1.0.6 面向 Kotlin 2.3/Compose 1.10.3：metadata 2.3 可被本项目 2.2.10 读取（n+1），
    // 公开 API 与 1.0.2 完全一致；得「绘制不更新」修复与 Shapes 圆角采样支持。
    // 2.0.1 需 Kotlin 2.4 metadata + Compose 1.12，属工具链 overhaul，不在此版。
    implementation("io.github.kyant0:backdrop:1.0.6")
    // 启动动画接管：最短停留让品牌动画播完（Android 12+ 系统 splash）
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // JVM 单测替换 android.jar 的 org.json stub（stub 全部方法抛异常）为真实实现
    testImplementation("org.json:json:20240303")
    // UI / Activity 层测试：在 JVM 上跑真实 Android 框架（此前该层完全无测试，
    // v1.3.1「点击开始转换闪退」正是发生在这层；单测只覆盖 core/ 转换管道）
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
    // 协程测试：Dispatchers.setMain 用测试调度器接管 Main。
    // 必要性：扫描器的状态复位在 withContext(Main) 内，而 Dispatchers.Main 会在
    // 首个 Robolectric 测试沙箱里被静态绑定到那个沙箱的 Looper——整套测试一起跑时
    // 后续用例的 idle() 推不动它，导致用例偶发失败。接管后完全确定、不依赖 Looper。
    // 版本与解析出的 kotlinx-coroutines-core 对齐
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Compose 截图测试（Robolectric 原生图形下渲染为位图）：本机没有真机/模拟器（无 KVM），
    // 这是唯一能「看到」真实渲染结果的手段，用于 UI 设计改进的目视验证
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
