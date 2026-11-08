plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "io.github.z3275630ops.floatingnotes"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.z3275630ops.floatingnotes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-t01"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test.junit4)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
}
