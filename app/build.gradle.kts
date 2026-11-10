plugins {
    id("com.android.application")
}

val releaseKeystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.fgn.ohomonet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fgn.ohomonet"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        // Use the shared debug keystore so local debug builds keep a stable
        // signature and can update an already installed build.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        releaseKeystorePath?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("OHO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OHO_KEY_ALIAS")
                keyPassword = System.getenv("OHO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
