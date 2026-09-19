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
import dev.mutwakil.androidide.tooling.api.LogSenderConfig.PROPERTY_LOGSENDER_ENABLED
import dev.mutwakil.androidide.tooling.api.LogSenderConfig._PROPERTY_LOGSENDER_LOCAL_AAR
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * @author Akash Yadav
 */
class AndroidIDEPluginTest {

  @Test
  fun `test logsender must be enabled by default`() {
    val result = buildProject()
    assertThat(result.output).doesNotContain("LogSender is disabled")
  }

  @Test
  fun `test logsender must be enabled if specified explicitly`() {
    val result = buildProject(configureArgs = {
      it.add("-P$PROPERTY_LOGSENDER_ENABLED=true")
    })
    assertThat(result.output).doesNotContain("LogSender is disabled")
  }

  @Test
  fun `test logsender must be disabled if specified explicitly`() {
    val result = buildProject(configureArgs = {
      it.add("-P$PROPERTY_LOGSENDER_ENABLED=false")
    })
    assertThat(result.output).contains("LogSender is disabled")
  }

  @Test
  fun `test logsender must be added as non-changing dependency`() {
    val result = buildProject(configureArgs = {
      it.add("--debug")
    })
    assertThat(result.output).contains("Marking logsender dependency as not-changing")
  }

  @Test
  fun `test explicit logsender on internal build fails open without bundled aar`() {
    val result = buildProject(
      pluginTestEnv = false,
      tasks = listOf(":app:checkDemoDebugAarMetadata", ":app:checkFullDebugAarMetadata"),
      configureArgs = {
        it.add("-P$PROPERTY_LOGSENDER_ENABLED=true")
      }
    )

    assertThat(result.output).contains(
      "Skipping optional LogSender instrumentation so the user's project can still build."
    )
    assertThat(result.output).doesNotContain("Could not find io.github.wadamzmail.androidide.logging:logsender")
  }

  @Test
  fun `test bundled logsender aar is preferred over maven`() {
    val aar = createMinimalLogSenderAar()

    val result = buildProject(
      tasks = listOf(":app:checkDemoDebugAarMetadata", ":app:checkFullDebugAarMetadata"),
      configureArgs = {
        it.add("-P$_PROPERTY_LOGSENDER_LOCAL_AAR=${aar.absolutePath}")
      }
    )

    assertThat(result.output).contains(
      "Adding LogSender dependency (bundled with AndroidIDE, file '${aar.name}')"
    )
    assertThat(result.output).doesNotContain(
      "Adding LogSender dependency (version '${depVersion(true)}' from Maven)"
    )
  }

  private fun createMinimalLogSenderAar(): File {
    val output = File("build/test-fixtures/logsender-local-test.aar")
    output.parentFile.mkdirs()

    val classesJar = ByteArrayOutputStream().use { bytes ->
      ZipOutputStream(bytes).use { /* valid empty classes.jar */ }
      bytes.toByteArray()
    }

    ZipOutputStream(output.outputStream().buffered()).use { zip ->
      zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zip.write(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="dev.mutwakil.androidide.logsender.test" />
        """.trimIndent().toByteArray()
      )
      zip.closeEntry()

      zip.putNextEntry(ZipEntry("classes.jar"))
      zip.write(classesJar)
      zip.closeEntry()
    }

    return output
  }
}
