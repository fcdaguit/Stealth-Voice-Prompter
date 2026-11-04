plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voiceprompter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voiceprompter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Vosk ships native .so files. Trim to the ABIs you actually target to
        // keep the APK small; add "armeabi-v7a" if you need older 32-bit devices.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Unsigned by default. CI signs this if the signing secrets are
            // configured; see .github/workflows/android.yml and README.
            signingConfig = null
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    // Room schema export — keeps a JSON record of each DB version in
    // app/schemas/, which you need to write migrations against later.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // MediaSessionCompat + MediaStyle notification. Needed so Bluetooth
    // clickers and headset buttons reach the app while the screen is off.
    implementation(libs.androidx.media)

    // Offline speech recognition
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }

    // Document import — deliberately NOT wired up yet.
    //
    // poi-ooxml in particular is a poor fit for Android: it pulls in log4j-api
    // and xmlbeans, which reference OSGi and bnd annotation classes that do
    // not exist on Android, and R8 refuses to shrink against unresolved
    // references. It also adds several MB to the APK.
    //
    // When the importer is written: .docx is just a zip containing
    // word/document.xml, so it can be parsed with the built-in XmlPullParser
    // and java.util.zip in a fraction of the size. Re-enable pdfbox-android
    // for PDF text extraction at that point — it is Android-specific and
    // behaves well.
    //
    // implementation(libs.pdfbox.android)
    // implementation(libs.poi.ooxml)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
}
