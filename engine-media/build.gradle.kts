plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kinetix3d.media"
    compileSdk = 37
    compileSdkMinor = 1
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        lintConfig = file("$rootDir/lint.xml")
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-evaluator"))
    implementation(project(":core-platform-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.transformer)
}
