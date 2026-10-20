plugins {
    id("com.android.application")
}

android {
    namespace = "com.juhyeon.chesstip"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.juhyeon.chesstip"
        minSdk = 34
        targetSdk = 36
        versionCode = 22
        versionName = "0.4.6"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libstockfish.so"
        }
    }
}

dependencies {
    implementation("com.github.bhlangonijr:chesslib:1.3.7")
    testImplementation("junit:junit:4.13.2")
}
