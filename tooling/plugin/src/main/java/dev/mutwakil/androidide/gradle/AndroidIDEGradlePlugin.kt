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
import dev.mutwakil.androidide.tooling.api.LogSenderConfig.PROPERTY_LOGSENDER_ENABLED
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging

/**
 * Gradle Plugin for projects built in AndroidIDE.
 *
 * @author Akash Yadav
 */
class AndroidIDEGradlePlugin : Plugin<Project> {

  companion object {

    private val logger = Logging.getLogger(AndroidIDEGradlePlugin::class.java)
  }

  override fun apply(target: Project) {
    if (target.isTestEnv) {
      logger.lifecycle("Applying ${javaClass.simpleName} to project '${target.path}'")
    }

    target.run {

      val isLogSenderEnabled = if (hasProperty(PROPERTY_LOGSENDER_ENABLED)) {
        property(PROPERTY_LOGSENDER_ENABLED).toString().toBoolean()
      } else {
        // Internal AndroidIDE builds use commit-scoped SNAPSHOT versions which are not guaranteed
        // to exist in Sonatype. Do not make ordinary project sync depend on that remote artifact.
        // Tests keep the historical behavior, and published builds can still inject LogSender.
        isTestEnv || !BuildInfo.VERSION_NAME_DOWNLOAD.endsWith("-SNAPSHOT", ignoreCase = true)
      }

      if (plugins.hasPlugin(APP_PLUGIN)) {

        if (isLogSenderEnabled) {
          logger.info("Trying to apply LogSender plugin to project '${project.path}'")
          applyLogSenderSafely()
        } else {
          logger.warn(
            "LogSender is disabled for project '${project.path}'. " +
              "Internal/SNAPSHOT IDE builds avoid unpublished remote LogSender artifacts."
          )
        }
      }
    }
  }

  /**
   * LogSender is an IDE convenience feature and must never make the user's actual project
   * unconfigurable. In particular, LinkageError covers classloader/API mismatches such as a
   * missing AGP ApplicationVariant class, while RuntimeException covers Gradle plugin application
   * failures. Both cases degrade gracefully to a build without LogSender.
   */
  private fun Project.applyLogSenderSafely() {
    try {
      pluginManager.apply(LogSenderPlugin::class.java)
    } catch (error: LinkageError) {
      logger.warn(
        "LogSender is incompatible with the current Android Gradle Plugin/classloader for " +
          "project '$path'. Continuing project configuration without LogSender.",
        error
      )
    } catch (error: RuntimeException) {
      logger.warn(
        "LogSender could not be configured for project '$path'. " +
          "Continuing project configuration without LogSender.",
        error
      )
    }
  }
}
