import java.util.Properties

plugins {
    id("com.android.application")
}

// Firebase (login + cloud backup) is optional: the Google Services plugin is only applied when
// app/google-services.json exists, so the project always builds and runs without it.
val hasFirebaseConfig = file("google-services.json").exists()
if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
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
        versionCode = 4
        versionName = "3.0"

        // Maps SDK key for the embedded map. Restrict it to "Maps SDK for Android" + this
        // package name and signing SHA-1 in Google Cloud Console.
        manifestPlaceholders["mapsApiKey"] = localProperties.getProperty("MAPS_API_KEY") ?: ""

        // The app talks only to our backend; Gemini and Places keys stay on the server.
        // 10.0.2.2 is the host machine as seen from the Android emulator.
        buildConfigField(
                "String",
                "BACKEND_URL",
                "\"${localProperties.getProperty("BACKEND_URL", "http://10.0.2.2:8000")}\""
        )
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasFirebaseConfig.toString())
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("com.google.android.gms:play-services-maps:20.1.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    // Key-free OpenStreetMap map, used when no Google Maps key is configured.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android's copy is a stub off-device).
    testImplementation("org.json:json:20250517")
    // Robolectric runs the real screens on the JVM for UI smoke tests (no emulator needed).
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
}

// Optional: point Robolectric's runtime download of the Android framework jar at a Maven mirror,
// e.g. ./gradlew test -ProbolectricRepoUrl=https://maven-central.storage-download.googleapis.com/maven2/
tasks.withType<Test>().configureEach {
    // Robolectric needs these on JDK 17+ (Android Studio's bundled JDK included).
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED")
    (project.findProperty("robolectricRepoUrl") as String?)?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
    // Optional end-to-end contract test against a running backend (see BackendContractTest).
    (project.findProperty("contractBackendUrl") as String?)?.let { systemProperty("contractBackendUrl", it) }
    // Optional PNG renders of the main screens for visual review (see ScreenRenderTest).
    (project.findProperty("renderScreens") as String?)?.let { systemProperty("renderScreens", it) }
}
