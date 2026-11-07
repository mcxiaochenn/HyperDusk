plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}

// 固定签名：debug 使用仓库内提交的 keystore；release 由 CI 通过环境变量提供（本地可选）。
val debugKeystoreFile = file("debug.keystore")
val releaseKeystoreFile = System.getenv("RELEASE_KEYSTORE_FILE")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.mcxiaochen.hyperdusk"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.mcxiaochen.hyperdusk"
        minSdk = 37
        targetSdk = 37
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // 固定签名（CI 与本地一致，避免每次构建签名变化导致升级要卸载重装）：
    //  - debug：使用仓库内提交的 android/app/debug.keystore（公开的 debug 签名，无安全风险）
    //  - release：CI 从 GitHub Secrets 解码后通过 RELEASE_KEYSTORE_FILE 指向临时文件；
    //    本地未提供这些环境变量时，release 构建退化为 debug 签名（保证本地可构建，仅用于验证）。
    signingConfigs {
        getByName("debug") {
            storeFile = debugKeystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (releaseKeystoreFile != null) {
                storeFile = releaseKeystoreFile
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            signingConfig = if (releaseKeystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        resources.merges += "META-INF/xposed/*"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
