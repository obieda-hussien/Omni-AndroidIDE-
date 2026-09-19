#!/usr/bin/env python3
"""Audit project creation assets before publishing an AndroidIDE APK.

Only standard-library modules are used so this runs before Gradle/Android SDK setup.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "core/app/src/main"
ASSETS = APP / "assets"
TEMPLATES = (
    "BasicActivity", "BottomNavigationActivity", "ComposeEmptyActivity",
    "EmptyActivity", "GameActivity", "NativeCpp",
    "NavigationDrawerActivity", "NoActivity", "ResponsiveActivity",
)
DENSITIES = ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
ERRORS = []


def require(condition, message):
    if not condition:
        ERRORS.append(message)


def icon(res, folder, name):
    return any((res / folder / f"{name}.{extension}").is_file()
               for extension in ("png", "webp"))


for template in TEMPLATES:
    base = ASSETS / template
    wrapper = base / "gradle/wrapper"
    for file in (
        base / "gradle/gradlew",
        base / "gradle/gradlew.bat",
        wrapper / "gradle-wrapper.jar",
        wrapper / "gradle-wrapper.properties",
    ):
        require(file.is_file() and file.stat().st_size > 0 if file.exists() else False,
                f"{template}: required wrapper file missing or empty: {file}")

    props = wrapper / "gradle-wrapper.properties"
    if props.is_file():
        require("distributionUrl=" in props.read_text(),
                f"{template}: Gradle wrapper has no distribution URL")

    res = base / "resources"
    adaptive = next(
        (res / dirname for dirname in ("mipmap-anydpi-v26", "mipmap-anydpi")
         if (res / dirname).is_dir()),
        None,
    )
    require(adaptive is not None, f"{template}: adaptive launcher icon folder missing")
    if adaptive:
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            file = adaptive / name
            require(file.is_file(), f"{template}: missing {file}")
            if file.is_file():
                try:
                    ET.parse(file)
                except ET.ParseError as error:
                    ERRORS.append(f"{template}: invalid adaptive launcher icon {file}: {error}")

    for density in DENSITIES:
        for name in ("ic_launcher", "ic_launcher_round"):
            require(icon(res, "mipmap-" + density, name),
                    f"{template}: missing {density} {name} PNG/WebP icon")

    source = APP / "java/dev/mutwakil/androidide/templates/android" / (template + ".kt")
    text = source.read_text()
    require("TemplateAssets.install(context, ASSETS_BASE_PATH, projectRoot)" in text,
            f"{template}: must use validated shared resource/wrapper installer")
    require("minSdk = options.minSdk" in text,
            f"{template}: must respect the user's selected minimum SDK")
    require("TemplateAssets.escapeXml(options.projectName)" in text,
            f"{template}: must safely encode the generated app label")

constants = (ROOT / "utilities/templates-api/src/main/java/dev/mutwakil/androidide/templates/constants.kt").read_text()
match = re.search(r'const val KOTLIN_VERSION = "(\d+)\.', constants)
require(bool(match and int(match.group(1)) >= 2),
        "Generated Compose templates require Kotlin 2.x and the bundled Compose compiler plugin")

compose = (APP / "java/dev/mutwakil/androidide/templates/android/ComposeEmptyActivity.kt").read_text()
require('id("org.jetbrains.kotlin.plugin.compose")' in compose,
        "Compose plugin must be declared in the generated version catalog")
require('versionRef("kotlin")' in compose,
        "Compose compiler plugin must use the Kotlin version catalog reference")
require("Compose projects require Kotlin." in compose,
        "Compose must reject Java-only project generation")
require("app/src/main/kotlin/" in compose,
        "Compose theme sources must use the Kotlin source directory")

if ERRORS:
    for error in ERRORS:
        print("ERROR: " + error, file=sys.stderr)
    sys.exit(1)

print(f"Verified {len(TEMPLATES)} templates: wrappers, icon densities, adaptive icons, Kotlin/Compose settings.")
