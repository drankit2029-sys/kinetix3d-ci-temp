plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.kinetix3d.studio"
    compileSdk = 37
    // API 37 is shipped as a minor-versioned SDK (android-37.1).
    compileSdkMinor = 1
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.kinetix3d.studio"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        lintConfig = file("$rootDir/lint.xml")
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":ui-studio"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
