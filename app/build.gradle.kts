plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.nm3u8.frontend"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nm3u8.frontend"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
        val binary = file("src/main/jniLibs/arm64-v8a/libN_m3u8DL-RE.so")
        if (!binary.exists()) throw GradleException("Missing ARM64 executable in jniLibs. Run the prepare-native workflow step.")
    }
}
tasks.named("preBuild").configure { dependsOn("verifyArm64Payload") }
