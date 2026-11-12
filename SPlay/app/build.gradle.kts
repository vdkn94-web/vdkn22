plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.splayer.splay"
    compileSdk { version = release(36) }

    defaultConfig {
        applicationId = "com.splayer.splay"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "2.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.tv.material)
    implementation(libs.tv.foundation)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.smbj)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
