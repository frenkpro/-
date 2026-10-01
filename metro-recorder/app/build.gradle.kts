plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Номер сборки растёт с каждым запуском GitHub Actions, поэтому новая версия
// ставится поверх старой без удаления (и без потери записей).
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.metro.recorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.metro.recorder"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Один и тот же ключ подписи для всех сборок: иначе Android откажется
    // обновлять приложение поверх предыдущей версии.
    signingConfigs {
        create("shared") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
