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
        applicationId = "com.vliveconvert.app"
        minSdk = 34
        targetSdk = 37
        versionCode = 27
        versionName = "1.7.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 只打 arm64（vivo 真机）与 x86_64（模拟器），减少 so 体积（对齐 ZLivePhoto）
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
            // 本地测试：release 挂 debug 签名，assembleRelease 产物可直接安装；
            // 正式发布时替换为正式签名配置
            signingConfig = signingConfigs.getByName("debug")
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
