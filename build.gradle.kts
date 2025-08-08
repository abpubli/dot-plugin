import org.gradle.kotlin.dsl.withType
import org.jetbrains.intellij.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "io.github.abpubli"
version = "1.8.0"

repositories {
    mavenCentral()
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
    withType<RunIdeTask> {
        jvmArgs("-Dide.browser.jcef.sandbox.enable=false")
    }

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
    <li>uses JCEF instead Batik for SVG</li>
    <li>significantly lighter distribution file</li>        
  </ul>
""".trimIndent())
    }
}
