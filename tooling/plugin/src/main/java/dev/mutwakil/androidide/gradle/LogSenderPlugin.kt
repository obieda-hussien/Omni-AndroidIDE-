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

import dev.mutwakil.androidide.tooling.api.LogSenderConfig._PROPERTY_LOGSENDER_LOCAL_AAR
import org.gradle.api.Named
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.logging.Logging
import java.io.File

/**
 * Plugin to manage LogSender in Android applications.
 *
 * This plugin intentionally does not link against AGP classes. AndroidIDE injects this plugin
 * from an init-script classloader, while AGP is normally loaded by the target project's plugin
 * classloader. A direct reference to classes such as ApplicationVariant can therefore fail while
 * Gradle is merely trying to instantiate/decorate this plugin.
 *
 * @author Akash Yadav
 */
class LogSenderPlugin : Plugin<Project> {

  companion object {

    private const val LOGSENDER_DEPENDENCY_ARTIFACT = "logsender"

    private val logger = Logging.getLogger(LogSenderPlugin::class.java)
  }

  override fun apply(target: Project) {
    if (target.isTestEnv) {
      logger.lifecycle("Applying ${javaClass.simpleName} to project '${target.path}'")
    }

    target.run {
      check(plugins.hasPlugin(APP_PLUGIN)) {
        "${javaClass.simpleName} can only be applied to Android application projects."
      }

      val debuggableBuildTypes = findDebuggableBuildTypes()

      logger.info(
        "Found ${debuggableBuildTypes.size} debuggable build types in project '${project.path}': " +
          debuggableBuildTypes
      )

      if (debuggableBuildTypes.isEmpty()) {
        logger.warn(
          "No debuggable build types were detected in project '${project.path}'. " +
            "Skipping LogSender dependency injection."
        )
        return@run
      }

      debuggableBuildTypes.forEach { buildType ->
        addLogSenderDependency(buildType)
      }
    }
  }

  /**
   * Reads Android build types without statically linking this plugin to AGP.
   *
   * The Android extension's buildTypes container is a Gradle Iterable, and build types implement
   * Gradle's Named contract. Only the "debuggable" property is read reflectively because its owner
   * type belongs to AGP.
   */
  private fun Project.findDebuggableBuildTypes(): Set<String> {
    val androidExtension = extensions.findByName("android")

    if (androidExtension == null) {
      logger.warn(
        "Android extension not found in project '$path'. " +
          "Falling back to the standard debug dependency bucket if available."
      )
      return fallbackDebugBuildType()
    }

    val buildTypes = runCatching {
      androidExtension.javaClass.methods
        .firstOrNull { method ->
          method.name == "getBuildTypes" && method.parameterCount == 0
        }
        ?.invoke(androidExtension)
    }.onFailure { error ->
      logger.warn(
        "Unable to inspect Android build types in project '$path'. " +
          "Falling back to the standard debug dependency bucket.",
        error
      )
    }.getOrNull()

    if (buildTypes !is Iterable<*>) {
      return fallbackDebugBuildType()
    }

    val result = linkedSetOf<String>()

    buildTypes.forEach { buildType ->
      if (buildType == null) {
        return@forEach
      }

      val name = when (buildType) {
        is Named -> buildType.name
        else -> readStringProperty(buildType, "getName")
      }

      if (name.isNullOrBlank()) {
        logger.warn("Ignoring an Android build type with no readable name in project '$path'")
        return@forEach
      }

      val debuggable = readBooleanProperty(
        buildType,
        "isDebuggable",
        "getDebuggable"
      )

      if (debuggable == true) {
        result += name
      }
    }

    if (result.isEmpty()) {
      return fallbackDebugBuildType()
    }

    return result
  }

  private fun Project.fallbackDebugBuildType(): Set<String> {
    return if (
      configurations.findByName("debugRuntimeOnly") != null ||
      configurations.findByName("debugImplementation") != null
    ) {
      logger.warn(
        "Using compatibility fallback for project '$path': treating the standard 'debug' " +
          "build type as debuggable."
      )
      setOf("debug")
    } else {
      emptySet()
    }
  }

  private fun Project.addLogSenderDependency(buildType: String) {
    val configuration = findDependencyBucket(buildType)

    if (configuration == null) {
      logger.warn(
        "Could not find a writable dependency bucket for debuggable build type '$buildType' " +
          "in project '$path'. Skipping LogSender for this build type."
      )
      return
    }

    val resolved = resolveLogSenderDependency() ?: return
    val logsenderDependency = resolved.first
    val sourceDescription = resolved.second

    if (logsenderDependency is ExternalModuleDependency) {
      // AndroidIDE pins the exact LogSender version. Avoid revalidating snapshot metadata on every
      // project sync when remote fallback is used.
      logger.debug("Marking logsender dependency as not-changing")
      logsenderDependency.isChanging = false

      val alreadyPresent = configuration.dependencies.any { existing ->
        existing.group == logsenderDependency.group &&
          existing.name == logsenderDependency.name &&
          existing.version == logsenderDependency.version
      }

      if (alreadyPresent) {
        logger.debug(
          "LogSender dependency is already present in configuration '${configuration.name}' " +
            "of project '$path'"
        )
        return
      }
    }

    configuration.dependencies.add(logsenderDependency)

    logger.lifecycle(
      "Adding LogSender dependency ($sourceDescription) " +
        "to debuggable build type '$buildType' via configuration '${configuration.name}' " +
        "of project '$path'"
    )
  }

  /**
   * Prefer the LogSender AAR shipped in the same AndroidIDE APK as this plugin. This keeps internal,
   * PR and offline builds self-contained and guarantees that the runtime matches the installed IDE.
   *
   * Released builds may fall back to the published Maven artifact. Commit-scoped SNAPSHOT builds
   * must never attempt a remote fallback because those artifacts are not guaranteed to be
   * published; missing optional instrumentation is preferable to failing the user's app build.
   */
  private fun Project.resolveLogSenderDependency(): Pair<Dependency, String>? {
    val localAarPath = findProperty(_PROPERTY_LOGSENDER_LOCAL_AAR)
      ?.toString()
      ?.trim()
      ?.takeIf { it.isNotEmpty() }

    if (localAarPath != null) {
      val localAar = File(localAarPath)
      if (localAar.isFile && localAar.canRead()) {
        logger.info("Using bundled LogSender AAR: ${localAar.absolutePath}")
        return dependencies.create(files(localAar)) to
          "bundled with AndroidIDE, file '${localAar.name}'"
      }

      logger.warn(
        "AndroidIDE provided a bundled LogSender path that is unavailable: '$localAarPath'."
      )
    }

    val version = depVersion(isTestEnv)
    if (!isTestEnv && version.endsWith("-SNAPSHOT", ignoreCase = true)) {
      logger.warn(
        "Bundled LogSender is unavailable for internal version '$version'. " +
          "Skipping optional LogSender instrumentation so the user's project can still build."
      )
      return null
    }

    val dependency = dependencies.ideDependency(
      LIB_GROUP_LOGGING,
      LOGSENDER_DEPENDENCY_ARTIFACT,
      isTestEnv
    )
    return dependency to "version '${dependency.version}' from Maven"
  }

  private fun Project.findDependencyBucket(buildType: String): Configuration? {
    val candidates = listOf(
      "${buildType}RuntimeOnly",
      "${buildType}Implementation"
    )

    return candidates.firstNotNullOfOrNull { name ->
      configurations.findByName(name)
    }
  }

  private fun readStringProperty(target: Any, methodName: String): String? {
    return runCatching {
      target.javaClass.methods
        .firstOrNull { method ->
          method.name == methodName && method.parameterCount == 0
        }
        ?.invoke(target) as? String
    }.getOrNull()
  }

  private fun readBooleanProperty(target: Any, vararg methodNames: String): Boolean? {
    methodNames.forEach { methodName ->
      val value = runCatching {
        target.javaClass.methods
          .firstOrNull { method ->
            method.name == methodName && method.parameterCount == 0
          }
          ?.invoke(target) as? Boolean
      }.getOrNull()

      if (value != null) {
        return value
      }
    }

    return null
  }
}
