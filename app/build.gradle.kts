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
        versionCode = 15
        versionName = "1.5.0"
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
        val arm64 = file("src/main/jniLibs/arm64-v8a")
        listOf("libN_m3u8DL-RE.so", "libffmpeg.so", "libmp4decrypt.so", "libopenssl-probe.so", "libc++_shared.so", "libffmpeg-launcher.so", "libmp4decrypt-launcher.so").forEach { name ->
            val binary = file("$arm64/$name")
            if (!binary.exists()) throw GradleException("Missing ARM64 executable $name in jniLibs. Run the prepare-native workflow step.")
        }
    }
}
tasks.named("preBuild").configure { dependsOn("verifyArm64Payload") }
