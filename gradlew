#!/bin/sh
set -eu
GRADLE_VERSION=9.5.0
BASE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/horatrack-gradle-$GRADLE_VERSION"
DIST_DIR="$BASE_DIR/gradle-$GRADLE_VERSION"
ZIP="$BASE_DIR/gradle-$GRADLE_VERSION-bin.zip"
if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  mkdir -p "$BASE_DIR"
  URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "curl ou wget est requis pour installer Gradle $GRADLE_VERSION" >&2
    exit 1
  fi
  rm -rf "$DIST_DIR"
  unzip -q "$ZIP" -d "$BASE_DIR"
fi
exec "$DIST_DIR/bin/gradle" "$@"
