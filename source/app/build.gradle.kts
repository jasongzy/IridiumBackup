import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.application.common)
    alias(libs.plugins.application.hilt)
    alias(libs.plugins.application.hilt.work)
    alias(libs.plugins.application.compose)
    alias(libs.plugins.refine)
}

val universalAssetsDir = layout.buildDirectory.dir("generated/universalAssets")
val generateUniversalAssets by tasks.registering(Sync::class) {
    into(universalAssetsDir)
    listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").forEach { abi ->
        from("src/$abi/assets/bin.zip") {
            into(abi)
        }
    }
}

tasks.matching {
    it.name != "generateUniversalAssets" &&
        it.name.contains("Universal") &&
        (it.name.endsWith("Assets") || it.name.contains("lint", ignoreCase = true))
}.configureEach {
    dependsOn(generateUniversalAssets)
}

android {
    namespace = "com.xayah.databackup"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jasongzy.databackup"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    lint {
        disable += "MissingTranslation"
    }

    androidResources {
        localeFilters += listOf("en", "zh-rCN")
    }

    flavorDimensions += listOf("abi", "feature")
    productFlavors {
        create("universal") {
            dimension = "abi"
        }
        create("arm64-v8a") {
            dimension = "abi"
            versionCode = 4 + (android.defaultConfig.versionCode ?: 0)
            ndk.abiFilters.add("arm64-v8a")
        }
        create("armeabi-v7a") {
            dimension = "abi"
            versionCode = 3 + (android.defaultConfig.versionCode ?: 0)
            ndk.abiFilters.add("armeabi-v7a")
        }
        create("x86_64") {
            dimension = "abi"
            versionCode = 2 + (android.defaultConfig.versionCode ?: 0)
            ndk.abiFilters.add("x86_64")
        }
        create("x86") {
            dimension = "abi"
            versionCode = 1 + (android.defaultConfig.versionCode ?: 0)
            ndk.abiFilters.add("x86")
        }
        create("foss") {
            dimension = "feature"
        }
        create("premium") {
            dimension = "feature"
            applicationIdSuffix = ".premium"
        }
        create("alpha") {
            dimension = "feature"
            applicationIdSuffix = ".alpha"
            versionCode = libs.versions.versionCodeAlpha.get().toInt()
            versionName = libs.versions.versionCodeAlpha.get()
        }
    }

    sourceSets.getByName("universal").assets.srcDir(generateUniversalAssets)

    applicationVariants.all {
        outputs.forEach { output ->
            (output as BaseVariantOutputImpl).outputFileName =
                "IridiumBackup-${versionName}-${productFlavors[0].name}-${productFlavors[1].name}-${buildType.name}.apk"
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Core
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:util"))
    implementation(project(":core:work"))
    compileOnly(project(":core:hiddenapi"))
    implementation(project(":core:rootservice"))

    // Feature
    implementation(project(":feature:crash"))
    implementation(project(":feature:setup"))
    "fossImplementation"(project(":feature:flavor:foss"))
    "premiumImplementation"(project(":feature:flavor:premium"))
    "alphaImplementation"(project(":feature:flavor:alpha"))
    "alphaImplementation"(project(":feature:flavor:foss"))
    implementation(project(":feature:main:dashboard"))
    implementation(project(":feature:main:restore"))
    implementation(project(":feature:main:cloud"))
    implementation(project(":feature:main:settings"))
    implementation(project(":feature:main:configurations"))
    implementation(project(":feature:main:processing"))
    implementation(project(":feature:main:list"))
    implementation(project(":feature:main:details"))
    implementation(project(":feature:main:directory"))

    // Splash Screen
    implementation(libs.androidx.core.splashscreen)

    // Compose Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // libsu
    implementation(libs.libsu.core)

    // BountyCastle
    implementation(libs.bountycastle)
}
