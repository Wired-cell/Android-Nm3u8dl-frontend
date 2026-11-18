plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.nm3u8.frontend"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nm3u8.frontend"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.register("verifyArm64Payload") {
    doLast {
        val binary = file("src/main/assets/native/N_m3u8DL-RE")
        if (!binary.exists()) throw GradleException("Missing Android Bionic N_m3u8DL-RE payload. Run the prepare-native workflow step.")
    }
}
tasks.named("preBuild").configure { dependsOn("verifyArm64Payload") }
