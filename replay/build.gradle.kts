plugins {
    kotlin("jvm") version "2.2.20"
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
