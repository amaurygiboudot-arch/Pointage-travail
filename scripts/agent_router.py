#!/usr/bin/env python3
import argparse
import fnmatch
import json
import subprocess
from collections import OrderedDict
from pathlib import Path


ROLE_RULES = OrderedDict([
    ("salary_v2", {
        "patterns": [
            "*Salary*", "*Payroll*", "*Payslip*", "*Convention*", "*Gross*",
            "*NetSalary*", "*Rgdu*", "*Meal*", "*Premium*", "*Seniority*",
            "app/src/main/java/com/amaury/pointage/v2/engine/*Contribution*",
        ],
        "reason": "paie, brut/net, conventions, bulletins, cotisations ou règles salariales",
    }),
    ("time_engine", {
        "patterns": [
            "*Pointage*", "*Pause*", "*Geofence*", "*Gps*", "*GPS*", "*TimeEngine*",
            "*RuntimeStore*", "*WorkSession*", "*Shift*", "*WidgetProvider*",
            "*QuickActionsWidget*", "*LocationManagement*",
        ],
        "reason": "pointage, temps, pauses, GPS, zones, sessions ou widgets",
    }),
    ("celestial_system", {
        "patterns": [
            "*Celestial*", "*SunIndicator*", "*EarthGlobe*", "*LightDirection*",
            "*ClockDial*", "*HpAnalogClock*", "*Moon*", "*SolarEclipse*",
            "*LunarEclipse*", "ios/HPTravail/HPTravail/CelestialV2/**",
        ],
        "reason": "Céleste, globe, Soleil/Lune, orientation, capteurs ou cadran céleste",
    }),
    ("ui_ux", {
        "patterns": [
            "app/src/main/res/layout/**", "app/src/main/res/drawable/**",
            "app/src/main/res/mipmap*/**", "*View.kt", "*Renderer.kt",
            "*UiInstaller.kt", "ios/HPTravail/HPTravail/**/*View.swift",
        ],
        "reason": "interface, rendu, navigation, accessibilité ou ressources visuelles",
    }),
    ("mobile_platforms", {
        "patterns": [
            "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
            "gradle.properties", "gradle/wrapper/**", "app/src/main/AndroidManifest.xml",
            "*Application.kt", "*Activity.kt", "*Worker.kt", "*Receiver.kt",
            "*CelestialTracker*", "*HpAnalogClock*", "*EarthGlobeRenderer*",
            "ios/**", "firebase.json", "google-services.json",
        ],
        "reason": "architecture Android/iOS, lifecycle, build, manifest, worker ou intégration plateforme",
    }),
    ("security_privacy", {
        "patterns": [
            "*Security*", "*Auth*", "*Biometric*", "*FirebaseAccount*",
            "firestore.rules", "storage.rules", "app/proguard-rules.pro",
            "SECURITY.md", ".github/CODEOWNERS",
        ],
        "reason": "sécurité, authentification, règles, obfuscation ou confidentialité",
    }),
    ("performance_battery", {
        "patterns": [
            "*Worker*", "*Tracker*", "*Renderer*", "*Sensor*", "*Location*",
            "*Background*", "*Cache*", "*Battery*", "*WorkManager*",
        ],
        "reason": "performance, cache, capteurs, arrière-plan, mémoire ou batterie",
    }),
    ("release_store", {
        "patterns": [
            "app/**", "ios/**",
            ".github/workflows/**", ".github/icon-build-trigger.txt",
            "app/build.gradle.kts", "build.gradle.kts", "gradle/**",
            "*Release*", "*Update*", "*Signing*", "app/src/main/AndroidManifest.xml",
        ],
        "reason": "gardien publication multi-plateforme : Android/iOS, CI/CD, artefacts, compatibilité, signature ou distribution",
    }),
    ("analytics_data", {
        "patterns": ["*Analytics*", "*Metric*", "*Telemetry*", "*EventStore*"],
        "reason": "analytique, métriques, instrumentation ou qualité de données",
    }),
    ("legal_compliance", {
        "patterns": [
            "*Official*", "*Legal*", "*Kali*", "*KALI*", "*Acco*", "*ACCO*",
            "*Jorf*", "*JORF*", "*Apec*", "*APEC*",
        ],
        "reason": "sources officielles, juridique, conformité ou interprétation conventionnelle",
    }),
])

GOVERNANCE_PATTERNS = [
    "AGENTS.md", ".codex/**", ".github/workflows/**", ".github/CODEOWNERS", "SECURITY.md"
]


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def changed_files(base: str, head: str) -> list[str]:
    output = git("diff", "--name-only", f"{base}...{head}")
    return [line.strip() for line in output.splitlines() if line.strip()]


def matches(path: str, patterns: list[str]) -> bool:
    name = Path(path).name
    return any(fnmatch.fnmatch(path, p) or fnmatch.fnmatch(name, p) for p in patterns)


def route(files: list[str], base: str, head: str) -> dict:
    specialists: list[str] = []
    reasons: dict[str, list[str]] = {}

    for role, config in ROLE_RULES.items():
        hits = [path for path in files if matches(path, config["patterns"])]
        if hits:
            specialists.append(role)
            reasons[role] = [config["reason"], *hits[:8]]

    governance_change = any(matches(path, GOVERNANCE_PATTERNS) for path in files)
    if governance_change:
        for role, reason in (
            ("security_privacy", "modification explicite de la gouvernance ou des contrôles"),
            ("people_ops", "organisation des rôles et chaîne de responsabilité"),
        ):
            if role not in specialists:
                specialists.append(role)
            reasons.setdefault(role, []).insert(0, reason)

    if not specialists:
        specialists = ["mobile_platforms"]
        reasons["mobile_platforms"] = [
            "route par défaut conservatrice : changement technique non classé"
        ]

    tests = ["codex-config"]
    android_touched = any(path.startswith("app/") or path.endswith(".kt") for path in files)
    ios_touched = any(path.startswith("ios/") for path in files)
    mobile_product_touched = android_touched or ios_touched
    functions_touched = any(path.startswith("functions/") for path in files)
    release_touched = any(
        path.startswith(".github/workflows/")
        or path in {"app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts"}
        or path.startswith("gradle/")
        for path in files
    )

    if mobile_product_touched:
        # HoraTrack vise Android + iOS : un lot mobile doit avoir une preuve
        # multi-plateforme au même HEAD avant d'être déclaré prêt.
        tests += ["v2-tests", "android-build", "play-build", "ios-tests", "ios-build"]
    elif release_touched:
        tests.append("play-build")
    if functions_touched:
        tests.append("functions-tests")

    seen = set()
    tests = [item for item in tests if not (item in seen or seen.add(item))]

    return {
        "schema_version": 1,
        "base_sha": base,
        "head_sha": head,
        "changed_files": files,
        "specialists": specialists,
        "required_chain": ["team_lead", "qa_reviewer", "control_gate"],
        "reasons": reasons,
        "recommended_tests": tests,
        "governance_change": governance_change,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Route une PR HoraTrack vers les agents requis.")
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--output")
    parser.add_argument("--pretty", action="store_true")
    args = parser.parse_args()

    base = git("rev-parse", args.base)
    head = git("rev-parse", args.head)
    result = route(changed_files(base, head), base, head)
    payload = json.dumps(result, ensure_ascii=False, indent=2 if args.pretty else None)

    if args.output:
        Path(args.output).write_text(payload + "\n", encoding="utf-8")
    print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
