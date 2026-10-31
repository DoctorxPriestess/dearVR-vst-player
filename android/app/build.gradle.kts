import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.android.application")
}

android {
    namespace = "com.vstplayer"
    compileSdk = 35
    // Pinned to the NDK that is actually installed here; AGP's default (27.x) is not present.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.vstplayer"
        minSdk = 24
        targetSdk = 28
        versionCode = 2
        versionName = "0.2"

        ndk {
            // Native ALAC/FLAC decoder; only arm64 devices are targeted.
            abiFilters += listOf("arm64-v8a")
        }
    }

    flavorDimensions += "env"
    productFlavors {
        create("standalone") {
            dimension = "env"
            // short id keeps <filesDir>/glibc/lib/ld-linux-aarch64.so.1 under the
            // 63-char PT_INTERP limit of the bundled binaries
            applicationId = "com.vstp"
            versionNameSuffix = "-full"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    androidResources {
        noCompress += listOf("bin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Keep the compiler honest without failing the build on library deprecations.
    lint {
        // The report is generated on demand (tools/build.sh lint); a broken report
        // should not block an APK that installs and runs.
        abortOnError = false
        checkReleaseBuilds = false
        // targetSdk 28 is a product decision: the library browses storage through legacy
        // paths, so raising it would change file access semantics for every user.
        // Play distribution is out of scope for this build.
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
}

dependencies {
    // Used by Env/FastExtract to unpack the xz-compressed asset segments.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
