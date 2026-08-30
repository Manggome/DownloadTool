plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// keystore/release.jks 가 있으면 릴리스 서명, 없으면 debug 빌드만 사용
val releaseKeystore = rootProject.file("keystore/release.jks")

// 저장소에 고정해 둔 debug 서명 키.
// 빌드마다 키가 바뀌면 기존 앱을 지워야만 설치되므로, 키를 고정해 덮어쓰기 업데이트가 되게 한다.
// debug 키는 관례상 비밀이 아니며(release 키는 위 시크릿 경로를 쓴다) CI 가 없으면 자동 생성한다.
val debugKeystore = rootProject.file("keystore/debug.jks")

android {
    namespace = "kr.neptune.linksaver"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.neptune.linksaver"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // ABI 제한은 아래 splits.abi 한 곳에서만 한다.
        // AGP 8.x 는 ndk.abiFilters 와 splits.abi 를 동시에 쓰면 설정 충돌로 실패한다.
    }

    signingConfigs {
        if (debugKeystore.exists()) {
            create("fixedDebug") {
                storeFile = debugKeystore
                storePassword = "linksaver"
                keyAlias = "linksaver"
                keyPassword = "linksaver"
            }
        }
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "linksaver"
                keyAlias = System.getenv("KEY_ALIAS") ?: "linksaver"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "linksaver"
            }
        }
    }

    buildTypes {
        release {
            // yt-dlp 래퍼가 Jackson 리플렉션을 쓰므로 난독화는 끔
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            if (debugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("fixedDebug")
            }
        }
    }

    // ABI 별로 APK 분리 → 용량 절감
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // python / yt-dlp 네이티브 페이로드는 반드시 추출되어야 함
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCY",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // yt-dlp + python + ffmpeg 번들
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")
}
