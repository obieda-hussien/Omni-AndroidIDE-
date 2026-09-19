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
import org.gradle.StartParameter
import org.gradle.api.Plugin
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import java.io.File
import java.io.FileNotFoundException

/**
 * Plugin for the AndroidIDE's Gradle Init Script.
 *
 * @author Akash Yadav
 */
class AndroidIDEInitScriptPlugin : Plugin<Gradle> {

  companion object {

    private val logger = Logging.getLogger(AndroidIDEInitScriptPlugin::class.java)
  }

  override fun apply(target: Gradle) {
    target.settingsEvaluated { settings ->
      settings.addDependencyRepositories()
    }

    // The init script already loads androidide-gradle-plugin.jar from AndroidIDE's private
    // ~/.androidide/plugin directory. Resolving the same plugin again from Maven makes internal
    // builds depend on ephemeral SNAPSHOT artifacts and can stall project sync for minutes.
    // Keep repository augmentation, but apply the plugin class directly from the init classloader.
    target.rootProject { rootProject ->
      rootProject.buildscript.repositories.addDependencyRepositories(
        rootProject.gradle.startParameter
      )
    }

    target.projectsLoaded { gradle ->
      gradle.rootProject.subprojects { sub ->
        if (!sub.buildFile.exists()) {
          // For subproject ':nested:module',
          // ':nested' represented as a 'Project', but it may or may not have a buildscript file
          // if the project doesn't have a buildscript, then the plugins should not be applied
          return@subprojects
        }

        sub.afterEvaluate {
          logger.info(
            "Applying local AndroidIDE Gradle plugin to project '${sub.path}' " +
              "without remote self-resolution"
          )
          sub.pluginManager.apply(AndroidIDEGradlePlugin::class.java)
        }
      }
    }
  }

  private fun Settings.addDependencyRepositories() {
    val (isTestEnv, mavenLocalRepos) = getTestEnvProps(startParameter)
    addDependencyRepositories(isTestEnv, mavenLocalRepos)
  }

  @Suppress("UnstableApiUsage")
  private fun Settings.addDependencyRepositories(
    isMavenLocalEnabled: Boolean,
    mavenLocalRepo: String
  ) {
    dependencyResolutionManagement.run {
      repositories.configureRepositories(isMavenLocalEnabled, mavenLocalRepo)
    }

    pluginManagement.apply {
      repositories.configureRepositories(isMavenLocalEnabled, mavenLocalRepo)
    }
  }

  private fun RepositoryHandler.addDependencyRepositories(startParams: StartParameter) {
    val (isTestEnv, mavenLocalRepos) = getTestEnvProps(startParams)
    configureRepositories(isTestEnv, mavenLocalRepos)
  }

  private fun getTestEnvProps(startParameter: StartParameter): Pair<Boolean, String> {
    return startParameter.run {
      val isTestEnv = projectProperties.containsKey(_PROPERTY_IS_TEST_ENV)
        && projectProperties[_PROPERTY_IS_TEST_ENV].toString().toBoolean()
      val mavenLocalRepos = projectProperties.getOrDefault(_PROPERTY_MAVEN_LOCAL_REPOSITORY, "")

      isTestEnv to mavenLocalRepos
    }
  }

  private fun RepositoryHandler.configureRepositories(
    isMavenLocalEnabled: Boolean,
    mavenLocalRepos: String
  ) {

    if (isMavenLocalEnabled) {
      logger.info("Using local maven repository for classpath resolution...")

      for (mavenLocalRepo in mavenLocalRepos.split(':')) {
        if (mavenLocalRepo.isBlank()) {
          mavenLocal()
        } else {
          logger.info("Local repository path: $mavenLocalRepo")

          val repo = File(mavenLocalRepo)
          if (!repo.exists() || !repo.isDirectory) {
            throw FileNotFoundException("Maven local repository '$mavenLocalRepo' not found")
          }

          maven { repository ->
            repository.url = repo.toURI()
          }
        }
      }
    }

    // Resolve normal Android/project dependencies from their canonical repositories first.
    // The AndroidIDE plugin itself is loaded directly from the init-script classloader, so user
    // builds no longer need the AndroidIDE snapshots repository injected ahead of every lookup.
    google()
    mavenCentral()
    gradlePluginPortal()

    // Keep AndroidIDE's public repository as a final fallback for released IDE artifacts such as
    // LogSender, without penalizing every ordinary dependency lookup.
    maven { repository ->
      repository.setUrl(BuildInfo.PUBLIC_REPOSITORY)
    }
  }
}