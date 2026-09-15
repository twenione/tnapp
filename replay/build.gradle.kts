plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":core-guide"))
}

application {
    mainClass.set("com.trailnav.replay.EngineCliKt")
}

kotlin {
    jvmToolchain(17)
}
