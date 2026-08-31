plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mcxiaochen.hyperdusk"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mcxiaochen.hyperdusk"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "APP_MODULE_ID", "\"com.mcxiaochen.hyperdusk\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
kotlin { jvmToolchain(25) }

dependencies {
    implementation(projects.library.common)
    implementation(projects.library.core)
    implementation(libs.libxposed.service)
}
