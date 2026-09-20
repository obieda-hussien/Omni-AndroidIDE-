#!/usr/bin/env bash
set -euo pipefail

# OmniLink v2 is a multi-module Gradle publication. The aggregate is POM-only:
# NEVER request OmniLinkSDK-v2.0.0.aar (that file cannot exist).
VERSION="${OMNILINK_VERSION:-v2.0.0}"
GROUP_PATH="com/github/obieda-hussien/OmniLinkSDK"
DEST_ROOT="${OMNILINK_LOCAL_REPO:-$PWD/ci-m2}"
BASE_URL="https://jitpack.io/$GROUP_PATH"

fetch() {
  local module="$1"
  local extension="$2"
  local dest="$DEST_ROOT/$GROUP_PATH/$module/$VERSION"
  local name="$module-$VERSION.$extension"
  local output="$dest/$name"
  local temp="$output.tmp"
  local url="$BASE_URL/$module/$VERSION/$name"
  local attempt=1
  local max_attempts=5

  mkdir -p "$dest"
  if [[ -s "$output" ]]; then
    echo "OmniLink $module $VERSION ($extension) is staged."
    return 0
  fi

  while (( attempt <= max_attempts )); do
    rm -f "$temp"
    echo "Fetching $url (attempt $attempt/$max_attempts)"
    local status
    status="$(curl --location --silent --show-error \
      --connect-timeout 20 --max-time 90 --retry 1 --retry-delay 3 \
      --output "$temp" --write-out '%{http_code}' "$url")" || status="000"

    if [[ "$status" == "200" && -s "$temp" ]]; then
      if [[ "$extension" == "aar" || "$extension" == "jar" ]]; then
        unzip -tqq "$temp" || {
          rm -f "$temp"
          echo "::error::Downloaded OmniLink artifact is not a valid ZIP/JAR/AAR."
          return 1
        }
      fi
      mv "$temp" "$output"
      echo "Staged $output"
      return 0
    fi

    rm -f "$temp"
    if [[ "$status" == "404" ]]; then
      echo "::error::OmniLink artifact is absent (404): $url"
      echo "::error::Check multi-module JitPack publication; do not retry a missing aggregate AAR."
      return 1
    fi
    if (( attempt == max_attempts )); then
      echo "::error::Unable to fetch OmniLink artifact (HTTP $status): $url"
      return 1
    fi
    local wait_seconds=$(( attempt * attempt * 5 ))
    if (( wait_seconds > 60 )); then wait_seconds=60; fi
    sleep "$wait_seconds"
    attempt=$(( attempt + 1 ))
  done
}

fetch omni-link-transport pom
fetch omni-link-transport jar
fetch omni-link-sdk pom
fetch omni-link-sdk aar

echo "OmniLinkSDK $VERSION Android + JVM modules staged in $DEST_ROOT"
