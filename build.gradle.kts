import org.jetbrains.kotlin.gradle.tasks.KotlinCompile // Import może być potrzebny dla withType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "io.github.abpubli"
version = "1.4.0"

repositories {
    mavenCentral()
}

intellij { // Target IDE Platform
    version.set("2024.3.5")
    type.set("IC")
    plugins.set(listOf()) // No plugin dependencies declared
}

tasks.named<org.jetbrains.intellij.tasks.BuildSearchableOptionsTask>("buildSearchableOptions") {
    enabled = false // Disabled due to non-fatal SEVERE errors (platform issue) logged by this task during build.
}


tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.addAll(listOf("-Xjsr305=strict", "-Xcontext-receivers"))
        }
    }

    patchPluginXml {
        sinceBuild.set("243")
        untilBuild.set("252.*")
        changeNotes.set("""
  <ul>
    <li>Prevents unwanted horizontal split</li>
    <li>Custom split handling with buttons</li>
    <li>Added fourth buton: toggle between horizontal and vertical split</li>
    <li>Preserved error highlighting</li>
  </ul>
""".trimIndent())
    }
}
