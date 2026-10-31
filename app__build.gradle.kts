plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Kotlin source package (unchanged). Yeh applicationId se alag cheez hai.
    namespace = "com.vscode.desktop.standalone"
    compileSdk = 34

    defaultConfig {
        // PERMANENT package ID — kabhi change mat karna, warna update "App not installed" dega.
        applicationId = "com.pocket.vscode"
        minSdk = 26
        targetSdk = 34

        // GitHub Actions mein har run ka number auto-increment hota hai => naya APK hamesha
        // purane ke upar update ho jata hai. Local build mein 1 use hota hai.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            // Android 10+ par libproot.so ko nativeLibraryDir (exec-allowed) mein extract karne ke liye.
            useLegacyPackaging = true
        }
    }

    // Bundled rootfs / VS Code archives pehle se compressed hain. Dobara compress na karein:
    // build tez hota hai aur assets seedhe openFd() se (length ke saath) padhe ja sakte hain,
    // jisse extraction ka real 0-100% progress milta hai.
    androidResources {
        noCompress += listOf("xz", "gz")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
