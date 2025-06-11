import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "io.github.abpubli"
version = "1.6.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.xmlgraphics:batik-transcoder:1.17") {
        exclude(group = "xml-apis", module = "xml-apis")
        exclude(group = "xerces", module = "xercesImpl") // 👈 to kluczowe
    }
}

intellij { // Target IDE Platform
    version.set("2023.2.8")
    type.set("PC")
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
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
        untilBuild.set(providers.gradleProperty("pluginUntilBuild"))
        changeNotes.set("""
  <ul>
    <li>Zoom</li>
    <li>Using svg vector to zoom image</li>
  </ul>
""".trimIndent())
    }
}
