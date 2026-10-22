plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.library.compose)
    alias(libs.plugins.library.androidTest)
}

android {
    namespace = "com.xayah.feature.main.settings"
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
    implementation(project(":core:rootservice"))
    implementation(project(":core:work"))
    implementation(project(":feature:setup"))

    // Compose Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)

    // dotLottie
    implementation(libs.dotlottie.android)

    // PickYou
    implementation(libs.pickyou)

    // Gson
    implementation(libs.gson)

    // Coil
    implementation(libs.coil.compose)

    androidTestImplementation(project(":core:database"))
    androidTestImplementation(libs.androidx.room.runtime)
}
