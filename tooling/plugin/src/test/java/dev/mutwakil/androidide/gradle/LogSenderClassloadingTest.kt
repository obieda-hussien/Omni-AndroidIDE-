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
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Regression coverage for AndroidIDE's init-script plugin classloader.
 *
 * Gradle inspects plugin methods while generating a decorated plugin class. Any AGP type leaked
 * into LogSenderPlugin's method descriptors can therefore fail before Plugin.apply() is invoked.
 */
class LogSenderClassloadingTest {

  @Test
  fun `log sender plugin can be introspected without AGP classes`() {
    val pluginClass = LogSenderPlugin::class.java

    // This mirrors the reflective inspection Gradle performs when decorating a plugin class.
    val methods = pluginClass.declaredMethods
    assertThat(methods).isNotEmpty()

    // Keep the injected plugin bytecode free of static AGP links. This catches fields, method
    // descriptors and implementation references even if JVM resolution happens to stay lazy.
    val classResource = pluginClass.name.replace('.', '/') + ".class"
    val classBytes = requireNotNull(pluginClass.classLoader.getResourceAsStream(classResource)) {
      "Unable to read $classResource"
    }.use { it.readBytes() }

    val constantPoolText = String(classBytes, StandardCharsets.ISO_8859_1)
    assertThat(constantPoolText).doesNotContain("com/android/build/api/")
  }
}
