import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Unit-test coverage is strictly opt-in: `-Pcoverage` (or COVERAGE=1) is the
// only thing that applies the jacoco plugin, so a plain `./gradlew test
// --offline` never has to resolve org.jacoco:* — an agent that would otherwise
// break offline builds on a warm-but-not-jacoco cache.
val coverageRequested: Boolean =
    providers.gradleProperty("coverage").isPresent ||
        providers.environmentVariable("COVERAGE").orNull == "1"

val coverageTaskName = "jacocoUnitTestReport"

fun Project.configureUnitTestCoverage() {
    if (tasks.names.contains(coverageTaskName)) return
    pluginManager.apply("jacoco")

    val androidModule =
        plugins.hasPlugin("com.android.library") || plugins.hasPlugin("com.android.application")
    val buildDir = layout.buildDirectory
    // AGP writes unit-test .exec files under outputs/unit_test_code_coverage;
    // the plain Gradle jacoco plugin (JVM modules) uses build/jacoco.
    val execDir = if (androidModule) "outputs/unit_test_code_coverage" else "jacoco"

    tasks.withType<Test>().configureEach {
        extensions.configure<JacocoTaskExtension> {
            isEnabled = true
            destinationFile = buildDir.file("$execDir/$name/$name.exec").get().asFile
        }
        finalizedBy(coverageTaskName)
    }

    tasks.register<JacocoReport>(coverageTaskName) {
        group = "verification"
        description = "Unit-test coverage for $path (XML + HTML)."
        dependsOn(tasks.withType<Test>())

        // Resolved at execution time, so it sees whatever the Test tasks wrote.
        executionData(fileTree(buildDir) { include("$execDir/**/*.exec") })
        classDirectories.setFrom(
            if (androidModule) {
                // Debug only: adding release too trips JaCoCo's
                // "different class with same name" because both variants are
                // compiled from the same sources.
                files(fileTree(buildDir) { include("tmp/kotlin-classes/debug/**") })
            } else {
                files(
                    fileTree(buildDir) { include("classes/kotlin/main/**") },
                    fileTree(buildDir) { include("classes/java/main/**") },
                )
            },
        )
        sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))

        reports {
            xml.required.set(true)
            html.required.set(true)
            xml.outputLocation.set(buildDir.file("reports/jacoco/$name/report.xml"))
            html.outputLocation.set(buildDir.dir("reports/jacoco/$name/html"))
        }
    }
}

if (coverageRequested) {
    subprojects {
        listOf("com.android.library", "com.android.application", "org.jetbrains.kotlin.jvm")
            .forEach { pluginId -> plugins.withId(pluginId) { configureUnitTestCoverage() } }
    }
}
