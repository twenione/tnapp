plugins {
    base
}

group = "com.trailnav"
version = "0.1.0"

tasks.register("phase0Check") {
    group = "verification"
    description = "Checks that the Phase 0 module skeleton is present."
    doLast {
        val expected = listOf("core-guide", "app", "replay", "synth")
        expected.forEach { module ->
            check(file(module).isDirectory) { "Missing module: $module" }
        }
        println("Phase 0 modules: ${expected.joinToString(", ")}")
    }
}

