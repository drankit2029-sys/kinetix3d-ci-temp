plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kinetix3d.platform"
    compileSdk = 37
    compileSdkMinor = 1
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
