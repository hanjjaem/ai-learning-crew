import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.go.donggu.photoheatmap"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.go.donggu.photoheatmap"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    // 모든 빌드를 같은 키로 서명해서, 새 버전을 지우지 않고 덮어써 설치할 수 있게 한다.
    // (시연용 디버그 키라 비밀값이 아님. Play 스토어 배포용 키로 쓰지 말 것)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
}
