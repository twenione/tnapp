plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("test")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
