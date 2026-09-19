/*
 * Android Template Creator - LGPL v3 or later.
 */
package dev.mutwakil.androidide.project.manager.builder.toplevel

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCatalogWriterTest {

  @Test
  fun matchingKotlinComposeVersionReferencesAreAccepted() {
    val text = VersionCatalogWriter().generate(
      versions = listOf(CatalogVersion("kotlin", "2.3.21")),
      plugins = listOf(
        CatalogPlugin("kotlin-android", "org.jetbrains.kotlin.android", versionRef = "kotlin"),
        CatalogPlugin("kotlin-compose", "org.jetbrains.kotlin.plugin.compose", versionRef = "kotlin")
      ),
      libraries = emptyList(),
      customSections = emptyList()
    )
    assertTrue(text.contains("version.ref = \"kotlin\""))
  }

  @Test
  fun undefinedVersionAliasesAreRejectedBeforeGradleSync() {
    assertThrows(IllegalArgumentException::class.java) {
      VersionCatalogWriter().generate(
        versions = listOf(CatalogVersion("agp", "8.13.2")),
        plugins = listOf(
          CatalogPlugin("kotlin-android", "org.jetbrains.kotlin.android", versionRef = "kotlin")
        ),
        libraries = emptyList(),
        customSections = emptyList()
      )
    }
  }

  @Test
  fun mismatchedComposeAndKotlinVersionsAreRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      VersionCatalogWriter().generate(
        versions = listOf(
          CatalogVersion("kotlin", "2.3.21"),
          CatalogVersion("compiler", "1.5.8")
        ),
        plugins = listOf(
          CatalogPlugin("kotlin-android", "org.jetbrains.kotlin.android", versionRef = "kotlin"),
          CatalogPlugin("kotlin-compose", "org.jetbrains.kotlin.plugin.compose", versionRef = "compiler")
        ),
        libraries = emptyList(),
        customSections = emptyList()
      )
    }
  }

  @Test
  fun duplicateLibraryAliasesAreRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      VersionCatalogWriter().generate(
        versions = emptyList(),
        plugins = emptyList(),
        libraries = listOf(
          CatalogLibrary("core", "androidx.core", "core", version = "1.18.0"),
          CatalogLibrary("core", "androidx.core", "core-ktx", version = "1.18.0")
        ),
        customSections = emptyList()
      )
    }
  }
}
