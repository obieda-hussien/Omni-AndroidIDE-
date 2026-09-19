/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */


import dev.mutwakil.androidide.build.config.BuildConfig
import dev.mutwakil.androidide.build.config.MVN_GROUP_ID
import dev.mutwakil.androidide.build.config.ProjectConfig

plugins {
  id("java-gradle-plugin")
  id("org.jetbrains.kotlin.jvm")
  id("com.vanniktech.maven.publish.base")
}


description = "Gradle Plugin for projects that are built with AndroidIDE"

tasks.named<Test>("test") {
  useJUnitPlatform()
}

dependencies {
  implementation(projects.tooling.pluginConfig)
  implementation(projects.utilities.buildInfo)

  // Keep AGP completely out of the plugin-under-test/runtime classpath.
  // The injected AndroidIDE plugin must be loadable before/independently from the target
  // project's AGP classloader. The TestKit sample project resolves AGP through its own
  // com.android.application plugin declaration, which mirrors real AndroidIDE usage.
  testImplementation(gradleTestKit())
  testImplementation(libs.tests.junit.jupiter)
  testImplementation(libs.tests.google.truth)
  testImplementation(projects.utilities.shared)

  testRuntimeOnly(libs.tests.junit.platformLauncher)
}

gradlePlugin {
  website.set(ProjectConfig.REPO_URL)
  vcsUrl.set(ProjectConfig.REPO_URL)

  plugins {
    create("initScriptPlugin") {
      id = "$MVN_GROUP_ID.init"
      implementationClass = "${BuildConfig.PACKAGE_NAME}.gradle.AndroidIDEInitScriptPlugin"
      displayName = "AndroidIDE Init Script Gradle Plugin"
      description = "Init script Gradle plugin for projects that are built with AndroidIDE"
      tags.set(setOf("androidide", "init"))
    }

    create("gradlePlugin") {
      id = MVN_GROUP_ID
      implementationClass = "${BuildConfig.PACKAGE_NAME}.gradle.AndroidIDEGradlePlugin"
      displayName = "AndroidIDE Gradle Plugin"
      description = "Gradle plugin for projects that are built with AndroidIDE"
      tags.set(setOf("androidide", "gradle"))
    }

    create("logsenderPlugin") {
      id = "$MVN_GROUP_ID.logsender"
      implementationClass = "${BuildConfig.PACKAGE_NAME}.gradle.LogSenderPlugin"
      displayName = "AndroidIDE LogSender Gradle Plugin"
      description = "Gradle plugin for applying LogSender-specific configuration to projects that are built with AndroidIDE"
      tags.set(setOf("androidide", "logsender"))
    }
  }
}

tasks.named<Jar>("jar") {
  archiveBaseName.set("androidide-gradle-plugin")
  archiveClassifier.set("") // Removes the default "all" classifier
  archiveVersion.set("")
}


val verifyLogSenderAgpIsolation by tasks.registering {
  group = "verification"
  description = "Verifies that the injected LogSender plugin has no static Android Gradle Plugin links."
  dependsOn(tasks.named("classes"))

  doLast {
    val classFiles = fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
      include("**/LogSenderPlugin*.class")
    }.files

    check(classFiles.isNotEmpty()) {
      "No compiled LogSenderPlugin classes were found."
    }

    val forbiddenPackage = "com/android/build/api/"
    val offendingFiles = classFiles.filter { classFile ->
      String(classFile.readBytes(), Charsets.ISO_8859_1).contains(forbiddenPackage)
    }

    check(offendingFiles.isEmpty()) {
      "LogSenderPlugin must remain AGP-classloader independent. Static AGP references found in: " +
        offendingFiles.joinToString { it.name }
    }
  }
}
