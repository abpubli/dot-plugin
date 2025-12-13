import org.gradle.kotlin.dsl.withType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.github.abpubli"
version = "1.8.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        clion("2025.3")
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
        changeNotes = """
            <ul>
                <li>uses JCEF instead Batik for SVG</li>
                <li>sanitizing SVG for security</li>
                <li>significantly lighter distribution file</li>
                <li>tested since 2023.2 until 2025.3</li>
            </ul>
            
            <b>Coming soon:</b> Dot Plus edition with syntax highlighting, 
            multiple rendering engines, and more!
        """.trimIndent()
    }
}

tasks {
    withType<RunIdeTask> {
        jvmArgs("-Dide.browser.jcef.sandbox.enable=false")
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}