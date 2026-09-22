#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[HoraTrack Cloud Setup] %s\n' "$*"
}

is_java17() {
  local javac_path="$1"

  [ -x "$javac_path" ] &&
    "$javac_path" -version 2>&1 | grep -Eq '^javac 17([. ]|$)'
}

java17_candidates() {
  local candidate

  if [ -n "${JAVA_HOME:-}" ]; then
    printf '%s\n' "${JAVA_HOME}/bin/javac"
  fi

  command -v javac 2>/dev/null || true

  if command -v update-alternatives >/dev/null 2>&1; then
    update-alternatives --list javac 2>/dev/null || true
  fi

  for candidate in \
    /usr/lib/jvm/*/bin/javac \
    /usr/java/*/bin/javac \
    /opt/java/*/bin/javac \
    "${HOME}"/.local/share/mise/installs/java/*/bin/javac \
    "${HOME}"/.sdkman/candidates/java/*/bin/javac; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
    fi
  done
}

select_java17() {
  local candidate

  while IFS= read -r candidate; do
    if is_java17 "$candidate"; then
      JAVAC_PATH="$(python3 - "$candidate" <<'PY'
from pathlib import Path
import sys

print(Path(sys.argv[1]).resolve())
PY
)"
      JAVA_HOME_RESOLVED="$(dirname "$(dirname "$JAVAC_PATH")")"
      return 0
    fi
  done < <(java17_candidates)

  return 1
}

run_root() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    # Le setup Cloud n'a aucun terminal utilisateur pour répondre à sudo.
    sudo -n -- "$@"
  else
    log "Droits administrateur indisponibles pour installer JDK 17."
    return 1
  fi
}

install_java17() {
  if command -v apt-get >/dev/null 2>&1; then
    log "Installation d'OpenJDK 17 via apt."
    run_root apt-get update
    run_root env DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jdk
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

if ! select_java17; then
  install_java17
fi

if ! select_java17; then
  log "JDK 17 reste indisponible après la configuration."
  exit 1
fi

log "JDK détecté : $("$JAVAC_PATH" -version 2>&1)"
log "JAVA_HOME=$JAVA_HOME_RESOLVED"

BASHRC="${HOME}/.bashrc"
PROFILE="${HOME}/.profile"
BEGIN_MARKER="# >>> HORATRACK CODEX CLOUD JAVA17 >>>"
END_MARKER="# <<< HORATRACK CODEX CLOUD JAVA17 <<<"

persist_java_env() {
  local target="$1"
  local placement="$2"

  python3 - "$target" "$BEGIN_MARKER" "$END_MARKER" "$JAVA_HOME_RESOLVED" "$placement" <<'PY'
from pathlib import Path
import shlex
import sys

path = Path(sys.argv[1])
begin = sys.argv[2]
end = sys.argv[3]
java_home = sys.argv[4]
placement = sys.argv[5]
text = path.read_text(encoding="utf-8") if path.exists() else ""

# Valider tous les marqueurs avant de préparer la moindre réécriture. Un bloc
# imbriqué ou incomplet doit laisser le fichier utilisateur strictement intact.
cursor = 0
while True:
    next_begin = text.find(begin, cursor)
    next_end = text.find(end, cursor)
    if next_begin == -1 and next_end == -1:
        break
    if next_begin == -1 or next_end == -1 or next_end < next_begin:
        raise SystemExit(
            f"Refus de modifier {path}: marqueurs HoraTrack incomplets ou désordonnés"
        )
    nested_begin = text.find(begin, next_begin + len(begin), next_end)
    if nested_begin != -1:
        raise SystemExit(
            f"Refus de modifier {path}: marqueurs HoraTrack imbriqués"
        )
    cursor = next_end + len(end)

while begin in text:
    start = text.index(begin)
    finish_start = text.find(end, start + len(begin))
    finish = finish_start + len(end)
    before = text[:start].rstrip("\n")
    after = text[finish:].lstrip("\n")
    text = before + ("\n" if before and after else "") + after

block = "\n".join(
    (
        begin,
        f"export JAVA_HOME={shlex.quote(java_home)}",
        'export PATH="$JAVA_HOME/bin:$PATH"',
        end,
        "",
    )
)

if placement == "prepend":
    text = block + text.lstrip("\n")
else:
    if text and not text.endswith("\n"):
        text += "\n"
    text += block

path.write_text(text, encoding="utf-8")
PY
}

# Le bloc doit précéder le garde non interactif présent sur certaines images
# Cloud, sinon bash quitte .bashrc avant d'exporter le JDK sélectionné.
persist_java_env "$BASHRC" prepend
persist_java_env "$PROFILE" append

export JAVA_HOME="$JAVA_HOME_RESOLVED"
export PATH="$JAVA_HOME/bin:$PATH"

log "Validation Gradle/JDK."
./gradlew --version

log "Validation configuration multi-agents."
python3 scripts/validate_codex_agents.py

log "Configuration Cloud prête."
