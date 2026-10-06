import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 正式版签名：keystore.properties 里放 storeFile / 密码 / 别名（不进版本库，别弄丢）。
// 文件不存在时（比如换台机器只编 debug）就跳过签名，构建不会因此失败。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null &&
    rootProject.file(keystoreProps.getProperty("storeFile")).exists()

android {
    namespace = "com.blazkness.quarkscanextractor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.blazkness.quarkscanextractor"
        minSdk = 24
        targetSdk = 37
        // versionCode：平台判断"是不是新版本"用的整数（比已装的低就不能覆盖安装）。
        // versionName：给人看的字符串，显示在系统设置里。
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // debug 包固定用仓库里这一份密钥，不用 AGP 在各机器上现场解析出的那把。
        // AGP 找默认 debug keystore 的路径是随平台变的（Linux 上从 ~/.config/.android/ 读，
        // Windows 上从 ~/.android/ 读），找不到就随机生成一把新的 —— 那样 CI 出来的包和
        // 本地 Android Studio 构建的签名不一致，无法互相覆盖安装。写死就没有这个变数。
        // debug 密钥是公开约定（别名 androiddebugkey / 密码 android），不是机密。
        val repoDebugKeystore = rootProject.file("keystore/debug.keystore")
        if (repoDebugKeystore.exists()) {
            getByName("debug") {
                storeFile = repoDebugKeystore
                storePassword = "android"
                keyAlias = "AndroidDebugKey"
                keyPassword = "android"
            }
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 关掉 v1/v2 之外的其余校验开关保持默认：v1+v2+v3 全开，装机范围最广
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 正式版开 R8（混淆 + 去掉无用代码），APK 会从 debug 的 11.7MB 缩到几 MB。
            // 保留规则见 app/proguard-rules.pro —— 那里最重要的一条是 Shizuku 用户服务类
            // 不能被混淆，否则 release 包连不上 Shizuku。
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}