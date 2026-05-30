plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.lookaway"
    compileSdk = 36  // Standardized configuration for Android 16 / SDK 36

    defaultConfig {
        applicationId = "com.example.lookaway"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            // This tells the compiler to look inside your custom folder for the Java classes!
            java.srcDirs("src/main/kotlin+java")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // ViewBinding removed: MainActivity uses standard findViewById, making this obsolete.

    bundle {
        language { enableSplit = false }
        density { enableSplit = false }
        abi { enableSplit = false }
    }

    packaging {
        jniLibs {
            // Extracts libraries smoothly for modern runtime packaging compatibility
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // OpenCV engine
    implementation("org.opencv:opencv:4.12.0")

    // Core UI and Appcompat
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)

    // Navigation fragments removed to reduce APK size and build time

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}