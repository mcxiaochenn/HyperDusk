plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sevtinge.hyperceiler.core"
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
    api(projects.library.common)
    api(projects.library.provision)
}
