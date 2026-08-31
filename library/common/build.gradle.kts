import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sevtinge.hyperceiler.common"
    compileSdk = 37

    defaultConfig {
        minSdk = 37

        buildConfigField("String", "APP_MODULE_ID", "\"com.mcxiaochen.hyperdusk\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {}
        create("beta") {}
        create("canary") {}
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(libs.core)
    // libxposed API 102
    compileOnlyApi(libs.libxposed.api)
}
