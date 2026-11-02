plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "org.nextcolor.dashcamviewer"
version = "0.1.0"
kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}
