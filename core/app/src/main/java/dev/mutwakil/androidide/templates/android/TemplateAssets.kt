/*
 * This file is part of AndroidIDE, licensed under the GNU GPL v3 or later.
 */
package dev.mutwakil.androidide.templates.android

import android.content.Context
import java.io.File
import java.io.FileNotFoundException

/**
 * Copies the generated project's mandatory wrapper and Android resources from the installed APK.
 * A missing or partially copied asset must fail creation, not produce a project that fails later
 * during Gradle sync or resource linking.
 */
internal object TemplateAssets {

  fun install(context: Context, templateName: String, projectRoot: File) {
    val root = "$templateName/gradle"
    copyFile(context, "$root/gradlew", File(projectRoot, "gradlew"))
    copyFile(context, "$root/gradlew.bat", File(projectRoot, "gradlew.bat"))
    copyDirectory(
      context,
      "$root/wrapper",
      File(projectRoot, "gradle/wrapper")
    )
    val wrapper = File(projectRoot, "gradle/wrapper")
    require(File(wrapper, "gradle-wrapper.jar").isFile) {
      "Missing gradle-wrapper.jar for $templateName"
    }
    require(File(wrapper, "gradle-wrapper.properties").isFile) {
      "Missing gradle-wrapper.properties for $templateName"
    }

    // External Android storage may not support chmod. Do not mistake that for a missing script.
    File(projectRoot, "gradlew").setExecutable(true, false)

    val resources = File(projectRoot, "app/src/main/res")
    copyDirectory(context, "$templateName/resources", resources)
    validateLauncherIcons(resources)
  }

  private fun copyDirectory(context: Context, path: String, dest: File) {
    val names = context.assets.list(path)
      ?: throw FileNotFoundException("Cannot list required template assets: $path")
    require(names.isNotEmpty()) { "Required template asset directory is missing or empty: $path" }
    require(dest.isDirectory || dest.mkdirs()) { "Cannot create directory: ${dest.absolutePath}" }

    for (name in names) {
      val child = "$path/$name"
      val target = File(dest, name)
      val children = context.assets.list(child).orEmpty()
      if (children.isEmpty()) {
        // The leaf must really be a file. A missing or empty directory is a generation error.
        copyFile(context, child, target)
      } else {
        copyDirectory(context, child, target)
      }
    }
  }

  private fun copyFile(context: Context, source: String, target: File) {
    val parent = target.parentFile ?: error("No parent for ${target.absolutePath}")
    require(parent.isDirectory || parent.mkdirs()) { "Cannot create directory: ${parent.absolutePath}" }
    val temp = File(parent, target.name + ".new")
    try {
      context.assets.open(source).use { input ->
        temp.outputStream().use { output -> input.copyTo(output) }
      }
      require(temp.isFile && temp.length() > 0L) { "Empty template asset: $source" }
      if (target.exists()) require(target.delete()) { "Cannot replace ${target.absolutePath}" }
      require(temp.renameTo(target)) { "Cannot install template asset: ${target.absolutePath}" }
    } finally {
      if (temp.exists()) temp.delete()
    }
  }

  fun escapeXml(text: String): String = buildString(text.length) {
    for (char in text) {
      append(
        when (char) {
          '&' -> "&amp;"
          '<' -> "&lt;"
          '>' -> "&gt;"
          '"' -> "&quot;"
          '\'' -> "&apos;"
          else -> char.toString()
        }
      )
    }
  }

  private fun validateLauncherIcons(res: File) {
    val adaptive = listOf("mipmap-anydpi-v26", "mipmap-anydpi")
      .map { File(res, it) }
      .firstOrNull { it.isDirectory }
      ?: error("Missing adaptive launcher icon directory")
    for (name in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
      require(File(adaptive, name).isFile) {
        "Missing adaptive launcher icon: ${adaptive.absolutePath}/$name"
      }
    }

    for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
      val dir = File(res, "mipmap-$density")
      for (name in listOf("ic_launcher", "ic_launcher_round")) {
        require(listOf("webp", "png").any { File(dir, "$name.$it").isFile }) {
          "Missing $density launcher icon: $name (PNG or WebP)"
        }
      }
    }
  }
}
