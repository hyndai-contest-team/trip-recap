import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 키와 공유 페이지 주소는 repo 에 넣지 않는다. local.properties(git 제외)에서 읽는다
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "kr.triprecap.app"
    compileSdk = 37
    // AAOS 표준 Car API (차속 PERF_VEHICLE_SPEED, 시동 IGNITION_STATE, 기어 GEAR_SELECTION 대체 경로, 주행 제한 CarUxRestrictions)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "kr.triprecap.app"
        minSdk = 28           // Pleos Connect 배포 요구사항: API 28 이상
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        // 카카오 로컬 REST API 키 (kakao.restApiKey). 없으면 장소 이름을 "방문지 n"으로 저장한다
        buildConfigField("String", "KAKAO_REST_API_KEY", "\"${localProps.getProperty("kakao.restApiKey", "")}\"")
        // 휴대폰 공유 페이지 주소. 비밀 값이 아니므로 배포 주소를 기본값으로 둔다. local.properties 의 share.baseUrl 이 있으면 그 값을 쓴다
        buildConfigField("String", "SHARE_BASE_URL", "\"${localProps.getProperty("share.baseUrl", "https://trip-recap.pages.dev/")}\"")
    }

    // release 서명 키는 repo 에 넣지 않는다. keystore.properties(git 제외)에 storeFile·storePassword·keyAlias·keyPassword 를 둔다.
    // 파일이 없으면 release APK 는 서명 없이 만들어진다(설치할 수 없다)
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // JVM 단위 테스트에서 android.util.Log 같은 스텁이 예외 대신 기본값을 돌려주게 한다
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fusedlocation)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.zxing.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM 단위 테스트에서 실제 org.json 구현을 쓴다 (android.jar 의 org.json 은 스텁)
    testImplementation(libs.org.json)
    // Room 저장소를 JVM 에서 실제 SQLite 로 시험한다
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
