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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        lintConfig = file("$rootDir/lint.xml")
    }

    // Generated Rust .so files land in build/ (never src/) and are merged by
    // AGP's native-libs tasks.
    sourceSets {
        getByName("debug") {
            jniLibs.directories += layout.buildDirectory.dir("rustJniLibs/debug").get().asFile.absolutePath
        }
        getByName("release") {
            jniLibs.directories += layout.buildDirectory.dir("rustJniLibs/release").get().asFile.absolutePath
        }
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

// --- Rust native core (cargo) -------------------------------------------------
// The Android NDK ships no Linux/ARM64 host toolchain (linux-x86_64 only), so
// on ARM hosts (the Termux build phone) we cross-compile only the target that
// matches the host (self-target: pkg clang + bionic sysroot). x86_64 hosts
// (PC, CI runners) drive cargo-ndk against the real NDK for all four ABIs.
val isX86_64Host = System.getProperty("os.arch") in setOf("x86_64", "amd64")
val defaultRustAbis = if (isX86_64Host) {
    listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
} else {
    listOf("arm64-v8a")
}
val rustAbis = (findProperty("rustAbis") as String? ?: defaultRustAbis.joinToString(","))
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

val abiToTriple = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86" to "i686-linux-android",
    "x86_64" to "x86_64-linux-android",
)

fun registerCargoBuild(buildType: String, release: Boolean): TaskProvider<Exec> {
    val buildTypeCap = buildType.replaceFirstChar { it.uppercase() }
    val outDir = layout.buildDirectory.dir("rustJniLibs/$buildType").get().asFile
    return tasks.register<Exec>("cargoBuild$buildTypeCap") {
        group = "rust"
        description = "Builds the Rust native core ($buildType) for ${rustAbis.joinToString()}"
        // Belt-and-braces: opted out of CC serialization; with CC-safe inputs
        // it should be a no-op.
        notCompatibleWithConfigurationCache("invokes cargo external process")
        workingDir = file("rust")
        inputs.files(fileTree(project.layout.projectDirectory.dir("rust"))
            .include("**/*.rs", "**/Cargo.toml", "**/Cargo.lock")
            .exclude("target/**"))
        inputs.property("rustAbis", rustAbis)
        outputs.dir(outDir)
        doFirst {
            outDir.deleteRecursively()
        }
        if (isX86_64Host) {
            // cargo-ndk: platform/toolchain handled by the NDK
            val args2 = mutableListOf("ndk", "-o", outDir.absolutePath, "--platform", "26")
            rustAbis.forEach { abi -> args2 += listOf("-t", abi) }
            args2 += "build"
            if (release) args2 += "--release"
            commandLine("cargo", *args2.toTypedArray())
        } else {
            // ARM host (Termux): pkg clang self-target, sysroot ships with the
            // rust-std packages — no NDK clang available.
            val triple = abiToTriple.getValue("arm64-v8a")
            val cmd = mutableListOf("cargo", "build", "--target", triple)
            if (release) cmd += "--release"
            commandLine(*cmd.toTypedArray())
            doLast {
                val profileDir = if (release) "release" else "debug"
                val src = file("rust/target/$triple/$profileDir/libkinetix_audio.so")
                val dst = File(outDir, "arm64-v8a/libkinetix_audio.so")
                check(src.exists()) { "cargo did not produce $src" }
                dst.parentFile.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
    }
}

val cargoBuildDebug = registerCargoBuild("debug", release = false)
val cargoBuildRelease = registerCargoBuild("release", release = true)

tasks.matching {
        it.name == "mergeDebugJniLibFolders" ||
            it.name == "mergeReleaseJniLibFolders" ||
            it.name == "mergeDebugNativeLibs" ||
            it.name == "mergeReleaseNativeLibs"
    }.configureEach {
        val isDebug = name.contains("Debug")
        dependsOn(if (isDebug) cargoBuildDebug else cargoBuildRelease)
        inputs.dir(layout.buildDirectory.dir(if (isDebug) "rustJniLibs/debug" else "rustJniLibs/release"))
    }
