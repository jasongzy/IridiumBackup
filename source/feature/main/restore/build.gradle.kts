plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.library.compose)
    alias(libs.plugins.library.androidTest)
}

android {
    namespace = "com.xayah.feature.main.restore"
    packaging.resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:datastore"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:util"))

    // Compose Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coil
    implementation(libs.coil.compose)

    // dotLottie
    implementation(libs.dotlottie.android)

    androidTestImplementation(project(":core:database"))
    androidTestImplementation(project(":core:rootservice"))
    androidTestImplementation(libs.androidx.room.runtime)
}
