#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[HoraTrack Cloud Setup] %s\n' "$*"
}

has_java17() {
  command -v javac >/dev/null 2>&1 &&
    javac -version 2>&1 | grep -Eq '^javac 17([. ]|$)'
}

run_root() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    sudo "$@"
  else
    log "Droits administrateur indisponibles pour installer JDK 17."
    return 1
  fi
}

install_java17() {
  if command -v apt-get >/dev/null 2>&1; then
    log "Installation d'OpenJDK 17 via apt."
    run_root apt-get update
    DEBIAN_FRONTEND=noninteractive run_root apt-get install -y openjdk-17-jdk
    return
  fi

  if command -v dnf >/dev/null 2>&1; then
    log "Installation d'OpenJDK 17 via dnf."
    run_root dnf install -y java-17-openjdk-devel
    return
  fi

  if command -v yum >/dev/null 2>&1; then
    log "Installation d'OpenJDK 17 via yum."
    run_root yum install -y java-17-openjdk-devel
    return
  fi

  log "Aucun gestionnaire de paquets pris en charge n'est disponible."
  return 1
}

if ! has_java17; then
  install_java17
fi

if ! has_java17; then
  log "JDK 17 reste indisponible après la configuration."
  exit 1
fi

JAVAC_PATH="$(command -v javac)"
JAVA_HOME_RESOLVED="$(dirname "$(dirname "$(readlink -f "$JAVAC_PATH")")")"

log "JDK détecté : $(javac -version 2>&1)"
log "JAVA_HOME=$JAVA_HOME_RESOLVED"

BASHRC="${HOME}/.bashrc"
BEGIN_MARKER="# >>> HORATRACK CODEX CLOUD JAVA17 >>>"
END_MARKER="# <<< HORATRACK CODEX CLOUD JAVA17 <<<"

if [ -f "$BASHRC" ]; then
  python3 - "$BASHRC" "$BEGIN_MARKER" "$END_MARKER" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
begin = sys.argv[2]
end = sys.argv[3]
text = path.read_text(encoding="utf-8")
if begin in text and end in text:
    start = text.index(begin)
    finish = text.index(end, start) + len(end)
    text = text[:start].rstrip() + "\n" + text[finish:].lstrip("\n")
    path.write_text(text, encoding="utf-8")
PY
fi

{
  printf '%s\n' "$BEGIN_MARKER"
  printf 'export JAVA_HOME=%q\n' "$JAVA_HOME_RESOLVED"
  printf 'export PATH="$JAVA_HOME/bin:$PATH"\n'
  printf '%s\n' "$END_MARKER"
} >> "$BASHRC"

export JAVA_HOME="$JAVA_HOME_RESOLVED"
export PATH="$JAVA_HOME/bin:$PATH"

log "Validation Gradle/JDK."
./gradlew --version

log "Validation configuration multi-agents."
python3 scripts/validate_codex_agents.py

log "Configuration Cloud prête."
