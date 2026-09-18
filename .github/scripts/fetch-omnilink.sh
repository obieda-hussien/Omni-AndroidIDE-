#!/usr/bin/env bash
set -euo pipefail

VERSION="${OMNILINK_VERSION:-v1.1.0}"
GROUP_PATH="com/github/obieda-hussien/OmniLinkSDK"
ARTIFACT="OmniLinkSDK-${VERSION}"
DEST_ROOT="${OMNILINK_LOCAL_REPO:-$PWD/ci-m2}"
DEST_DIR="$DEST_ROOT/$GROUP_PATH/$VERSION"
BASE_URL="https://jitpack.io/$GROUP_PATH/$VERSION/$ARTIFACT"

mkdir -p "$DEST_DIR"

fetch() {
  local ext="$1"
  local target="$DEST_DIR/$ARTIFACT.$ext"
  local tmp="$target.tmp"
  local attempt=1
  local max_attempts=6

  if [[ -s "$target" ]]; then
    echo "OmniLinkSDK $VERSION .$ext already available in local CI Maven repo."
    return 0
  fi

  while (( attempt <= max_attempts )); do
    echo "Fetching OmniLinkSDK $VERSION .$ext (attempt $attempt/$max_attempts)..."
    rm -f "$tmp"
    if curl       --fail       --location       --silent       --show-error       --connect-timeout 20       --max-time 75       --retry 1       --retry-delay 3       --retry-all-errors       --output "$tmp"       "$BASE_URL.$ext"; then
      test -s "$tmp"
      mv "$tmp" "$target"
      echo "Fetched $target"
      return 0
    fi

    rm -f "$tmp"
    if (( attempt == max_attempts )); then
      echo "::error::Failed to fetch OmniLinkSDK $VERSION .$ext from JitPack after $max_attempts attempts."
      return 1
    fi

    sleep_for=$(( 5 * (1 << (attempt - 1)) ))
    if (( sleep_for > 60 )); then sleep_for=60; fi
    echo "JitPack unavailable/rate-limited; retrying in ${sleep_for}s..."
    sleep "$sleep_for"
    attempt=$(( attempt + 1 ))
  done
}

fetch pom
fetch aar

echo "OmniLinkSDK $VERSION staged at $DEST_DIR"
