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

package dev.mutwakil.androidide.gradle

import dev.mutwakil.androidide.buildinfo.BuildInfo
import dev.mutwakil.androidide.tooling.api.LogSenderConfig._PROPERTY_IS_TEST_ENV
import dev.mutwakil.androidide.tooling.api.LogSenderConfig._PROPERTY_MAVEN_LOCAL_REPOSITORY
import dev.mutwakil.androidide.utils.FileProvider
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.internal.PluginUnderTestMetadataReading
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.pathString

internal fun buildProject(
  agpVersion: String = BuildInfo.AGP_VERSION_LATEST,
  gradleVersion: String = BuildInfo.AGP_VERSION_GRADLE_LATEST,
  useApplyPluginGroovySyntax: Boolean = false,
  pluginTestEnv: Boolean = true,
  configureArgs: (MutableList<String>) -> Unit = {},
  vararg plugins: String
): BuildResult {
  val projectRoot = openProject(agpVersion, useApplyPluginGroovySyntax, *plugins)
  val initScript = FileProvider.testHomeDir().resolve(".androidide/init/androidide.init.gradle")
  val mavenLocal = Paths.get("build/maven-local/repos.txt").toFile()

  if (!(mavenLocal.exists() && mavenLocal.isFile)) {
    throw FileNotFoundException("repos.txt file not found")
  }

  val repositories = mavenLocal.readText()

  for (repo in repositories.split(':')) {
    val file = File(repo)
    if (!(file.exists() && file.isDirectory)) {
      throw FileNotFoundException("Maven local repository does not exist : $repo")
    }
  }

  val args = mutableListOf(
    ":app:tasks", // run any task, as long as it applies the plugins
    "--init-script", initScript.pathString,
    "--stacktrace"
  )

  if (pluginTestEnv) {
    // Plugins should use artifacts staged in build-local Maven repositories for integration tests.
    args.add("-P$_PROPERTY_IS_TEST_ENV=true")
    args.add("-P$_PROPERTY_MAVEN_LOCAL_REPOSITORY=$repositories")
  }

  configureArgs(args)

  var testEnvironment: Map<String, String>? = null

  // Gradle 7.x cannot run on JDK 21. Keep compatibility tests honest by forcing the minimum
  // supported Gradle line onto JDK 17 from both directions:
  // 1) JAVA_HOME/PATH for the forked TestKit process, and
  // 2) org.gradle.java.home for the Gradle daemon itself.
  //
  // Setting only JAVA_HOME is insufficient when TestKit can reconnect to/reuse a daemon that was
  // initially started by the JDK 21 test JVM.
  if (gradleVersion.startsWith("7.")) {
    val java17Home = System.getenv("JAVA_HOME_17_X64")
      ?: System.getenv("JAVA_HOME_17")
      ?: System.getenv("ANDROIDIDE_TEST_JAVA17_HOME")
      ?: System.getProperty("java.home").takeIf {
        Runtime.version().feature() <= 17
      }

    require(!java17Home.isNullOrBlank()) {
      "Gradle $gradleVersion compatibility tests require JDK 17. " +
        "Set JAVA_HOME_17_X64 or ANDROIDIDE_TEST_JAVA17_HOME."
    }

    args.add("-Dorg.gradle.java.home=$java17Home")

    testEnvironment = System.getenv().toMutableMap().apply {
      this["JAVA_HOME"] = java17Home
      this["PATH"] = java17Home + File.separator + "bin" +
        File.pathSeparator + getOrDefault("PATH", "")
      this["GRADLE_OPTS"] = listOfNotNull(
        get("GRADLE_OPTS")?.takeIf { it.isNotBlank() },
        "-Dorg.gradle.java.home=$java17Home"
      ).joinToString(" ")
    }
  }

  val runner = GradleRunner.create()
    .withProjectDir(projectRoot.toFile())
    .withGradleVersion(gradleVersion)
    .withArguments(
      *args.toTypedArray()
    )

  testEnvironment?.let { runner.withEnvironment(it) }

  writeInitScript(
    initScript.toFile(),
    PluginUnderTestMetadataReading.readImplementationClasspath()
  )

  return runner.build()
}

internal fun writeInitScript(file: File, deps: List<File>) {
  file.parentFile.mkdirs()

  val root = FileProvider.projectRoot().pathString
  val depsString = deps.filter { it.absolutePath.startsWith(root) }
    .joinToString(separator = System.lineSeparator()) {
      "classpath files(\"${it}\")"
    }

  file.bufferedWriter().use {
    it.write(
      """
      initscript {
        dependencies {
          // make sure the init script plugin is in classpath
          $depsString
        }
      }
      
      apply plugin: dev.mutwakil.androidide.gradle.AndroidIDEInitScriptPlugin
    """.trimIndent()
    )
  }
}

internal fun openProject(
  agpVersion: String = BuildInfo.AGP_VERSION_LATEST,
  useApplyPluginGroovySyntax: Boolean = false,
  vararg plugins: String
): Path {
  val projectRoot = Paths.get("src/test/resources/sample-project")

  run {
    projectRoot.resolve("build.gradle.kts").toFile()
      .replaceAllPlaceholders(mapOf("AGP_VERSION" to agpVersion))
  }

  run {
    // remove existing build scripts
    projectRoot.resolve("app")
      .toFile()
      .listFiles()!!
      .filter { it.name.startsWith("build.gradle") && !it.name.endsWith(".in") }
      .forEach { it.delete() }

    val pluginsText = if (!useApplyPluginGroovySyntax) {
      plugins.joinToString(separator = "\n") { "id(\"$it\")" }
    } else {
      plugins.joinToString(separator = "\n") { "apply plugin: \"$it\"" }
    }

    projectRoot.resolve("app/build.gradle" + if (useApplyPluginGroovySyntax) "" else ".kts")
      .toFile()
      .replaceAllPlaceholders(mapOf("PLUGINS" to pluginsText))
  }

  return projectRoot
}

private fun File.replaceAllPlaceholders(entries: Map<String, String>) {
  val sb = StringBuilder(parentFile.resolve("${name}.in").readText())
  for ((placeholder, value) in entries) {
    val regex = Regex.escape("@@${placeholder}@@").toRegex()
    val result = regex.findAll(sb)
    for (matchResult in result) {
      sb.replace(matchResult.range.first, matchResult.range.last + 1, value)
    }
  }
  writeText(sb.toString())
}