/*
 * Android Template Creator - LGPL v3 or later.
 */
package dev.mutwakil.androidide.project.manager.builder.module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleGradleWriterTest {

  private fun composeConfig(): ModuleGradleConfig = moduleGradleConfig {
    addPlugin(GradlePlugin("id", "com.android.application"))
    addPlugin(GradlePlugin("id", "org.jetbrains.kotlin.android"))
    addPlugin(GradlePlugin("id", "org.jetbrains.kotlin.plugin.compose"))
    namespace("com.example.template")
    compileSdk(36)
    defaultConfig(DefaultConfig("com.example.template", minSdk = 23, targetSdk = 36))
    javaVersion(JavaVersion.VERSION_17)
    addBuildFeature(BuildFeature.COMPOSE)
    enableCompose(true)
  }

  @Test
  fun composeGeneratesKotlin2CompilerPluginWithoutLegacyExtension() {
    for (type in GradleFileType.entries) {
      val script = MLGradleWriter().generate(type, composeConfig())
      assertTrue(script.contains("org.jetbrains.kotlin.plugin.compose"))
      assertFalse(script.contains("kotlinCompilerExtensionVersion"))
      assertFalse(script.contains("composeOptions {"))
      assertTrue(script.contains("jvmTarget.set("))
      val androidEnd = script.indexOf("\n}\n")
      val kotlinDsl = script.indexOf("\nkotlin {")
      assertTrue("Kotlin DSL must be outside android {}", androidEnd in 0 until kotlinDsl)
      assertTrue(script.contains("compose"))
    }
  }

  @Test
  fun obsoleteCompilerVersionFailsDuringProjectCreation() {
    assertThrows(IllegalArgumentException::class.java) {
      moduleGradleConfig {
        addPlugin(GradlePlugin("id", "org.jetbrains.kotlin.plugin.compose"))
        namespace("com.example.template")
        compileSdk(36)
        defaultConfig(DefaultConfig("com.example.template", minSdk = 23, targetSdk = 36))
        addBuildFeature(BuildFeature.COMPOSE)
        enableCompose(true, "1.5.8")
      }
    }
  }

  @Test
  fun composeWithoutKotlinComposePluginFailsEarly() {
    assertThrows(IllegalArgumentException::class.java) {
      moduleGradleConfig {
        addPlugin(GradlePlugin("id", "com.android.application"))
        namespace("com.example.template")
        compileSdk(36)
        defaultConfig(DefaultConfig("com.example.template", minSdk = 23, targetSdk = 36))
        addBuildFeature(BuildFeature.COMPOSE)
      }
    }
  }

  @Test
  fun impossibleSdkRangeFailsDuringGeneration() {
    assertThrows(IllegalArgumentException::class.java) {
      moduleGradleConfig {
        namespace("com.example.template")
        compileSdk(34)
        defaultConfig(DefaultConfig("com.example.template", minSdk = 36, targetSdk = 36))
      }
    }
  }

  @Test
  fun javaOnlyProjectsDoNotGenerateKotlinCompilerBlock() {
    val config = moduleGradleConfig {
      addPlugin(GradlePlugin("id", "com.android.application"))
      namespace("com.example.template")
      compileSdk(36)
      defaultConfig(DefaultConfig("com.example.template", minSdk = 23, targetSdk = 36))
      enableKotlinOptions(false)
    }
    for (type in GradleFileType.entries) {
      assertFalse(MLGradleWriter().generate(type, config).contains("\nkotlin {"))
    }
  }
}
