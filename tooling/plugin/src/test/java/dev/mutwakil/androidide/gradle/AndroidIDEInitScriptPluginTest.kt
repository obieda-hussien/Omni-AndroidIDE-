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

import com.google.common.truth.Truth.assertThat
import dev.mutwakil.androidide.buildinfo.BuildInfo
import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Test

/**
 * @author Akash Yadav
 */
class AndroidIDEInitScriptPluginTest {

  @Test
  fun `test plugins are applied and log sender dependency is added properly`() {
    val result = buildProject()
    assertBasics(result)
  }

  @Test
  fun `test behavior on minimum supported version`() {
    val result = buildProject(agpVersion = BuildInfo.AGP_VERSION_MININUM, gradleVersion = "7.5.1")
    assertBasics(result)
  }

  @Test
  fun `test behavior with apply plugin syntax`() {
    val result = buildProject(
      agpVersion = BuildInfo.AGP_VERSION_MININUM,
      gradleVersion = "7.5.1",
      useApplyPluginGroovySyntax = true
    )
    assertBasics(result)
  }

  private fun assertBasics(result: BuildResult) {
    // These plugins must be applied to the Android app module.
    for ((project, plugins) in mapOf(
      ":app" to arrayOf(AndroidIDEGradlePlugin::class, LogSenderPlugin::class))) {
      for (plugin in plugins) {
        assertThat(result.output).contains(
          "Applying ${plugin.simpleName} to project '${project}'"
        )
      }
    }

    // One build-type-scoped bucket covers every product flavor that uses that debuggable build
    // type (demoDebug, fullDebug, and any future flavor) without depending on AGP variant classes.
    assertThat(result.output).contains(
      "Adding LogSender dependency (version '${depVersion(true)}' from Maven) " +
        "to debuggable build type 'debug' via configuration 'debugRuntimeOnly' " +
        "of project ':app'"
    )

    // Release must stay clean.
    assertThat(result.output).doesNotContain(
      "to debuggable build type 'release' via configuration 'releaseRuntimeOnly'"
    )

    // Regression guard for the on-device failure that used to happen while Gradle decorated the
    // plugin before its apply() method could even run.
    assertThat(result.output).doesNotContain(
      "com/android/build/api/variant/ApplicationVariant"
    )
    assertThat(result.output).doesNotContain("Could not generate a decorated class for type LogSenderPlugin")
  }
}
