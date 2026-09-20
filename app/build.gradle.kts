plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun gitOutput(vararg command: String): String? = runCatching {
    ProcessBuilder(*command)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
        .inputStream
        .bufferedReader()
        .use { it.readText().trim() }
}.getOrNull()?.takeIf { it.isNotBlank() }

val gitSha = gitOutput("git", "rev-parse", "HEAD")
val gitDirty = gitOutput("git", "status", "--porcelain")?.isNotBlank() == true
val gitCodeHash = if (gitSha?.matches(Regex("[0-9a-fA-F]{40}")) == true) {
    "git:$gitSha${if (gitDirty) "-dirty" else ""}"
} else {
    "git:unknown"
}

android {
    namespace = "com.trailnav.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trailnav.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_CODE_HASH", "\"$gitCodeHash\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-guide"))
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

