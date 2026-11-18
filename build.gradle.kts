plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.anthropic:anthropic-java:3.3.0")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("MainKt") }
