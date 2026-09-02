#!/usr/bin/env python3
"""Keep every APK update entry point on the single verified update pipeline."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/amaury/pointage"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
RECOVERY = JAVA / "RecoveryActivityV2.kt"
LEGACY_RECOVERY = JAVA / "RecoveryActivity.kt"
UPDATE_CHECKER = JAVA / "UpdateChecker.kt"
VERIFICATION_WORKER = JAVA / "UpdateVerificationWorker.kt"
LATEST_RELEASE_API = (
    "https://api.github.com/repos/amaurygiboudot-arch/Pointage-travail/releases/latest"
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def main() -> None:
    kotlin_sources = list(JAVA.rglob("*.kt"))
    contents = {path: path.read_text(encoding="utf-8") for path in kotlin_sources}

    require(not LEGACY_RECOVERY.exists(), "l ancien ecran de recuperation existe encore")
    require(
        all("RecoveryUpdater" not in source for source in contents.values()),
        "un second moteur RecoveryUpdater a ete reintroduit",
    )

    api_owners = [path for path, source in contents.items() if LATEST_RELEASE_API in source]
    require(
        api_owners == [UPDATE_CHECKER],
        f"l API latest release doit appartenir uniquement a UpdateChecker: {api_owners}",
    )

    recovery_source = contents[RECOVERY]
    require("UpdateChecker.check(" in recovery_source, "la recuperation ne passe pas par UpdateChecker")
    require(
        "recoveryRepair = true" in recovery_source,
        "la demande de reparation n est pas transmise au worker securise",
    )
    for forbidden in ("DownloadManager", "HttpURLConnection", "ApkUpdateVerifier"):
        require(
            forbidden not in recovery_source,
            f"la recuperation reimplemente une etape interdite: {forbidden}",
        )

    checker_source = contents[UPDATE_CHECKER]
    require(
        "BuildConfig.INTERNAL_APK_UPDATES_ENABLED" in checker_source,
        "l interrupteur Google Play n est plus centralise dans UpdateChecker",
    )
    require("fun openInstaller(" not in checker_source, "un contournement direct de la verification APK existe")

    worker_source = contents[VERIFICATION_WORKER]
    require(
        worker_source.count("ApkUpdateVerifier.verify(") == 1,
        "le worker doit appeler exactement une fois le verificateur APK complet",
    )
    verify_position = worker_source.index("ApkUpdateVerifier.verify(")
    ready_position = worker_source.index("UpdateChecker.markDownloadReady(")
    clear_position = worker_source.index("CrashRecoveryManager.clear(")
    require(
        verify_position < ready_position < clear_position,
        "l etat de crash ne doit etre efface qu apres verification complete de l APK",
    )

    manifest_source = MANIFEST.read_text(encoding="utf-8")
    require(
        'android:name=".RecoveryActivityV2"' in manifest_source,
        "l ecran de recuperation actif manque dans le manifeste",
    )
    require(
        'android:name=".RecoveryActivity"' not in manifest_source,
        "l ancien ecran de recuperation reste declare dans le manifeste",
    )

    print("OK mises a jour: un seul chemin, controle APK complet et variante Play respectee")


if __name__ == "__main__":
    main()
