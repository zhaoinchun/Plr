plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.plr"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.plr"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        getByName("main") {
            res.srcDirs(
                "src/main/java/androidx/media3/ui/res",
                "src/main/res",
            )
            assets {
                srcDirs("src\\main\\assets", "src\\main\\assets")
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
//    implementation(libs.androidx.media3.common.ktx)
//    implementation(libs.androidx.media3.extractor)
    implementation(libs.material)
    implementation(libs.androidx.exifinterface)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    api(libs.guava) {
        // Exclude dependencies that are only used by Guava at compile time
        // (but declared as runtime deps) [internal b/168188131].
//      exclude(group = "com.google.code.findbugs", module = "jsr305")
//      exclude(group = "org.checkerframework", module = "checker-compat-qual")
//      exclude(group = "org.checkerframework", module = "checker-qual")
//      exclude(group = "com.google.errorprone", module = "error_prone_annotations")
//      exclude(group = "com.google.j2objc", module = "j2objc-annotations")
//      exclude(group = "org.codehaus.mojo", module = "animal-sniffer-annotations")
    }
}