plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.library.compose)
    alias(libs.plugins.library.androidTest)
}

android {
    namespace = "com.xayah.feature.main.details"
    packaging.resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:util"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    compileOnly(project(":core:hiddenapi"))
    implementation(project(":core:rootservice"))

    // Hilt navigation
    implementation(libs.androidx.hilt.navigation.compose)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
