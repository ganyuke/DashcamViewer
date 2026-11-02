import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

group = "org.nextcolor.dashcamviewer"
version = "0.1.0"
android {
    namespace = "org.nextcolor.dashcamviewer"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.nextcolor.dashcamviewer"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { debug { applicationIdSuffix = ".debug" } }
    val signingProperties = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE") ?: signingProperties.getProperty("storeFile")
    val releasePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: signingProperties.getProperty("storePassword")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = releasePassword
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: signingProperties.getProperty("keyAlias", "nextcolor")
                keyPassword = releasePassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":adapter"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.datastore.preferences)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
