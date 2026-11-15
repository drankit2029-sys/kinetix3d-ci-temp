plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.kinetix3d.ui"
    compileSdk = 37
    compileSdkMinor = 1
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
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
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-evaluator"))
    implementation(project(":core-platform-api"))
    implementation(project(":engine-media"))
    implementation(project(":engine-render"))
    implementation(project(":engine-ai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsize)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
