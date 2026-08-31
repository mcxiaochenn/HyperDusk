plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.lsparanoid)
}

lsparanoid {
    seed = 227263
    classFilter = { true }
    includeDependencies = true
    variantFilter = { variant ->
        variant.buildType != "debug"
    }
}

android {
    namespace = "com.sevtinge.hyperceiler.libhook"
    compileSdk = 37

    defaultConfig {
        minSdk = 37

        buildConfigField("String", "APP_MODULE_ID", "\"com.mcxiaochen.hyperdusk\"")
    }

    buildFeatures {
        aidl = true
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

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }

    compilerOptions {
        freeCompilerArgs.add("-XXLanguage:+MultiDollarInterpolation")
    }
}

dependencies {
    api(libs.core)
    compileOnlyApi(projects.library.hiddenApi)

    // libxposed API 102 (compileOnly, runs against framework on device)
    compileOnlyApi(libs.libxposed.api)
    api(libs.libxposed.service)

    api(libs.ezhooktool.core)
    api(libs.ezhooktool.xposed102)

    api(projects.library.processor)
    api(projects.library.common)
    annotationProcessor(projects.library.processor)
}
