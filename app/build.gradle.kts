import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.touristguide"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.touristguide"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "2.1"
        manifestPlaceholders["mapsApiKey"] = localProperties.getProperty("MAPS_API_KEY") ?: ""
        buildConfigField(
                "String",
                "GEMINI_API_KEY",
                "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\""
        )
        buildConfigField(
                "String",
                "GEMINI_MODEL",
                "\"${localProperties.getProperty("GEMINI_MODEL", "gemini-3.1-flash-lite")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        disable += listOf(
                "OldTargetApi",
                "GradleDependency",
                "AndroidGradlePluginVersion",
                "PluralsCandidate",
                "Overdraw",
                "UseCompoundDrawables"
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.gms:play-services-maps:20.1.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
}
