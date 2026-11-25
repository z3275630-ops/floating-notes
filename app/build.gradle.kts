import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.room)
}

val releaseSigningProps = Properties().apply {
    val userHomeCandidates = listOfNotNull(
        rootProject.file("release-signing.properties"),
        System.getenv("USERPROFILE")?.takeIf { it.isNotBlank() }?.let {
            file("$it/.android/floating-notes-signing.properties")
        },
        file("${System.getProperty("user.home")}/.android/floating-notes-signing.properties"),
    )
    userHomeCandidates.firstOrNull { it.isFile }?.bufferedReader(Charsets.UTF_8)?.use { load(it) }
}

val releaseStorePath = System.getenv("FLOATING_NOTES_STORE_FILE")
    ?: releaseSigningProps.getProperty("storeFile")
val releaseStorePassword = System.getenv("FLOATING_NOTES_STORE_PASSWORD")
    ?: releaseSigningProps.getProperty("storePassword")
val releaseKeyAlias = System.getenv("FLOATING_NOTES_KEY_ALIAS")
    ?: releaseSigningProps.getProperty("keyAlias")
val releaseKeyPassword = System.getenv("FLOATING_NOTES_KEY_PASSWORD")
    ?: releaseSigningProps.getProperty("keyPassword")
val releaseKeystoreFile = releaseStorePath?.takeIf { it.isNotBlank() }?.let { path ->
    val f = file(path)
    if (f.isAbsolute) f else rootProject.file(path)
}
val hasReleaseSigning = releaseKeystoreFile?.isFile == true &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    val splitApks = project.findProperty("splitApks") == "true"
    namespace = "io.github.z3275630ops.floatingnotes"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.z3275630ops.floatingnotes"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.2.8-splash-startup.1"
        buildConfigField("boolean", "MATERIAL_LAB", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("zh", "zh-rCN", "en")
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        debug {
            // AGP rejects configuring NDK ABI filters alongside ABI splits.
            if (!splitApks) {
                ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "MATERIAL_LAB", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (!splitApks) {
                ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            buildConfigField("boolean", "MATERIAL_LAB", "true")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    packaging {
        jniLibs {
            excludes += listOf("**/mips/**", "**/mips64/**", "**/armeabi/**", "**/x86/**")
        }
        resources {
            excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1", "DebugProbesKt.bin")
        }
    }
    if (splitApks) {
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a")
                isUniversalApk = false
            }
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
    sourceSets.getByName("benchmark").java.srcDir("src/release/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}
room {
    schemaDirectory(layout.projectDirectory.dir("schemas").asFile.absolutePath)
}
dependencies {
    implementation(libs.room.runtime)
    implementation(libs.vosk.android)
    kapt(libs.room.compiler)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test.junit4)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
}
