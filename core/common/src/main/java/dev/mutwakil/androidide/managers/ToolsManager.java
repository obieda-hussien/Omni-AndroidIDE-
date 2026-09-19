/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package dev.mutwakil.androidide.managers;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.ResourceUtils;
import dev.mutwakil.androidide.app.BaseApplication;
import dev.mutwakil.androidide.app.configuration.IDEBuildConfigProvider;
import dev.mutwakil.androidide.app.configuration.IJdkDistributionProvider;
import dev.mutwakil.androidide.utils.Environment;

import org.jetbrains.annotations.Contract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.io.ConstantsKt;
import kotlin.io.FilesKt;

public class ToolsManager {

  private static final Logger LOG = LoggerFactory.getLogger(ToolsManager.class);

  public static String COMMON_ASSET_DATA_DIR = "data/common";
  private static final AtomicBoolean GRADLE_INTEGRATION_VERIFIED = new AtomicBoolean(false);
  private static final AtomicBoolean LOGSENDER_AAR_READY = new AtomicBoolean(false);

  public static void init(@NonNull BaseApplication app, Runnable onFinish) {

    if (!IDEBuildConfigProvider.getInstance().supportsCpuAbi()) {
      LOG.error("Device not supported");
      return;
    }

    CompletableFuture.runAsync(() -> {
      // Load installed JDK distributions
      IJdkDistributionProvider.getInstance().loadDistributions();

      writeNoMediaFile();
      ensureGradleIntegrationCurrent();
      extractAapt2();
      extractToolingApi();
      extractAndroidJar();
      extractColorScheme(app);
      extractDownloadKtScript();

      deleteIdeenv();
    }).whenComplete((__, error) -> {
      if (error != null) {
        LOG.error("Error extracting tools", error);
      }

      if (onFinish != null) {
        onFinish.run();
      }
    });
  }

  /**
   * Keeps the IDE-owned Gradle init script and plugin JAR in sync with the currently installed APK.
   *
   * Older builds only refreshed init.gradle.bak and left an existing init.gradle untouched. After
   * an APK upgrade that stale script could keep resolving a commit-scoped tooling SNAPSHOT that no
   * longer exists, making every project sync fail. This verifier is safe to call before each build:
   * it performs the disk refresh at most once per process and only owns files under ~/.androidide.
   */
  public static synchronized void ensureGradleIntegrationCurrent() {
    if (GRADLE_INTEGRATION_VERIFIED.get()) {
      return;
    }

    refreshAndroidIdeGradlePlugin();
    refreshBundledLogSender();
    refreshInitScript();
    GRADLE_INTEGRATION_VERIFIED.set(true);
  }

  private static void refreshAndroidIdeGradlePlugin() {
    final var target = Environment.ANDROIDIDE_GRADLE_PLUGIN_JAR;
    final var temp = new File(target.getParentFile(), target.getName() + ".new");

    FileUtils.delete(temp);
    if (!ResourceUtils.copyFileFromAssets(
        getCommonAsset("androidide-gradle-plugin.jar"),
        temp.getAbsolutePath())) {
      throw new IllegalStateException("Failed to extract AndroidIDE Gradle plugin from APK assets");
    }

    if (target.exists() && !FileUtils.delete(target)) {
      FileUtils.delete(temp);
      throw new IllegalStateException("Failed to replace stale AndroidIDE Gradle plugin");
    }
    if (!temp.renameTo(target)) {
      FileUtils.delete(temp);
      throw new IllegalStateException("Failed to activate AndroidIDE Gradle plugin");
    }
  }

  /**
   * Refreshes the LogSender AAR shipped inside the currently installed APK.
   *
   * LogSender is optional for project builds, so extraction failures must not block Gradle itself.
   * The readiness flag prevents a stale AAR left by an older APK from being injected after a
   * failed refresh.
   */
  private static void refreshBundledLogSender() {
    LOGSENDER_AAR_READY.set(false);

    final var target = Environment.ANDROIDIDE_LOGSENDER_AAR;
    final var temp = new File(target.getParentFile(), target.getName() + ".new");

    FileUtils.delete(temp);
    if (!ResourceUtils.copyFileFromAssets(
        getCommonAsset("logsender-release.aar"),
        temp.getAbsolutePath())) {
      FileUtils.delete(temp);
      LOG.warn("Bundled LogSender AAR is missing from APK assets; project builds will continue without it");
      return;
    }

    if (target.exists() && !FileUtils.delete(target)) {
      FileUtils.delete(temp);
      LOG.warn("Unable to replace stale bundled LogSender AAR; project builds will continue without it");
      return;
    }

    if (!temp.renameTo(target)) {
      FileUtils.delete(temp);
      LOG.warn("Unable to activate bundled LogSender AAR; project builds will continue without it");
      return;
    }

    LOGSENDER_AAR_READY.set(target.isFile() && target.canRead());
  }

  public static boolean isBundledLogSenderReady() {
    return LOGSENDER_AAR_READY.get();
  }
  
  private static void extractDownloadKtScript() {
    if (Environment.DOWNLOAD_KT_SCRIPT.exists()) {
      FileUtils.delete(Environment.DOWNLOAD_KT_SCRIPT);
    }

    ResourceUtils.copyFileFromAssets(getCommonAsset("download-kotlin-artifacts.sh"),
            Environment.DOWNLOAD_KT_SCRIPT.getAbsolutePath());
  }

  private static void extractColorScheme(final BaseApplication app) {
    final var defPath = "editor/schemes";
    final var dir = new File(Environment.ANDROIDIDE_UI, defPath);
    try {
      for (final String asset : app.getAssets().list(defPath)) {

        final var prop = new File(dir, asset + "/" + "scheme.prop");
        if (prop.exists() && !shouldExtractScheme(app, new File(dir, asset),
            defPath + "/" + asset)) {
          continue;
        }

        final File schemeDir = new File(dir, asset);
        if (schemeDir.exists()) {
          schemeDir.delete();
        }

        ResourceUtils.copyFileFromAssets(defPath + "/" + asset, schemeDir.getAbsolutePath());
      }
    } catch (IOException e) {
      LOG.error("Failed to extract color schemes", e);
    }
  }

  private static boolean shouldExtractScheme(final BaseApplication app, final File dir,
      final String path) throws IOException {

    final var schemePropFile = new File(dir, "scheme.prop");
    if (!schemePropFile.exists()) {
      return true;
    }

    final var files = app.getAssets().list(path);
    if (Arrays.stream(files).noneMatch("scheme.prop"::equals)) {
      // no scheme.prop file
      return true;
    }

    try {
      final var props = new Properties();
      Reader reader = new InputStreamReader(app.getAssets().open(path + "/scheme.prop"));
      props.load(reader);
      reader.close();

      final var version = Integer.parseInt(props.getProperty("scheme.version", "0"));
      if (version == 0) {
        return true;
      }

      props.clear();

      reader = new FileReader(schemePropFile);
      props.load(reader);
      reader.close();

      final var fileVersion = Integer.parseInt(props.getProperty("scheme.version", "0"));
      if (fileVersion < 0) {
        return true;
      }

      return version > fileVersion;
    } catch (Throwable err) {
      LOG.error("Failed to read color scheme version for scheme '{}'", path, err);
      return false;
    }
  }

  private static void writeNoMediaFile() {
    final var noMedia = new File(BaseApplication.getBaseInstance().getProjectsDir()
            , ".nomedia");
    if (!noMedia.exists()) {
      try {
        if (!noMedia.createNewFile()) {
          LOG.error("Failed to create .nomedia file in projects directory");
        }
      } catch (IOException e) {
        LOG.error("Failed to create .nomedia file in projects directory");
      }
    }
  }

  private static void extractAndroidJar() {
    if (Environment.ANDROID_JAR.isDirectory()) {
      Environment.ANDROID_JAR.delete();
    }
    if (!Environment.ANDROID_JAR.exists()) {
      ResourceUtils.copyFileFromAssets(getCommonAsset("android.jar"),
          Environment.ANDROID_JAR.getAbsolutePath());
    }
  }

  private static void deleteIdeenv() {
    final var file = new File(Environment.BIN_DIR, "ideenv");
    if (file.exists() && !file.delete()) {
      LOG.warn("Unable to delete file: {}", file);
    }
  }

  @NonNull
  @Contract(pure = true)
  public static String getCommonAsset(String name) {
    return COMMON_ASSET_DATA_DIR + "/" + name;
  }

  private static void extractAapt2() {
    if (!Environment.AAPT2.exists()) {
      final var context = BaseApplication.getBaseInstance();
      final var nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
      final var sourceAapt2 = new File(nativeLibraryDir, "libaapt2.so");
      if (sourceAapt2.exists() && sourceAapt2.isFile()) {
        FilesKt.copyTo(sourceAapt2, Environment.AAPT2, true, ConstantsKt.DEFAULT_BUFFER_SIZE);
      } else {
        LOG.error("{} file does not exist! This can be problematic.", sourceAapt2);
      }
    }

    if (!Environment.AAPT2.canExecute() && !Environment.AAPT2.setExecutable(true)) {
      LOG.error("Cannot set executable permissions to AAPT2 binary");
    }
  }

  private static void extractToolingApi() {
    if (Environment.TOOLING_API_JAR.exists()) {
      FileUtils.delete(Environment.TOOLING_API_JAR);
    }

    ResourceUtils.copyFileFromAssets(getCommonAsset("tooling-api-all.jar"),
        Environment.TOOLING_API_JAR.getAbsolutePath());
  }

  private static void refreshInitScript() {
    final var initScript = Environment.INIT_SCRIPT;
    final var initScriptBak = new File(initScript.getParentFile(), initScript.getName() + ".bak");
    final var initScriptNew = new File(initScript.getParentFile(), initScript.getName() + ".new");
    final var contents = readInitScript();

    final String existing;
    try {
      existing = initScript.exists()
          ? FilesKt.readText(initScript, StandardCharsets.UTF_8)
          : "";
    } catch (Throwable error) {
      LOG.warn("Failed reading existing Gradle init script; replacing it", error);
      FileUtils.delete(initScript);
      FilesKt.writeText(initScript, contents, StandardCharsets.UTF_8);
      FilesKt.writeText(initScriptBak, contents, StandardCharsets.UTF_8);
      return;
    }

    FilesKt.writeText(initScriptBak, contents, StandardCharsets.UTF_8);
    if (contents.equals(existing)) {
      return;
    }

    LOG.warn("Refreshing stale AndroidIDE Gradle init script after app/tooling upgrade");
    FileUtils.delete(initScriptNew);
    FilesKt.writeText(initScriptNew, contents, StandardCharsets.UTF_8);

    if (initScript.exists() && !FileUtils.delete(initScript)) {
      FileUtils.delete(initScriptNew);
      throw new IllegalStateException("Failed to remove stale AndroidIDE Gradle init script");
    }
    if (!initScriptNew.renameTo(initScript)) {
      FileUtils.delete(initScriptNew);
      throw new IllegalStateException("Failed to activate refreshed AndroidIDE Gradle init script");
    }
  }

  @NonNull
  private static String readInitScript() {
    return ResourceUtils.readAssets2String(getCommonAsset("androidide.init.gradle"));
  }

}
