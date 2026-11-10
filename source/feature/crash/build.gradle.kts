plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.compose)
}

android {
    namespace = "com.xayah.feature.crash"
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:util"))
}
