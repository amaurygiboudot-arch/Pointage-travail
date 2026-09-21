#!/usr/bin/env python3
import sys
import tomllib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
config_path = root / ".codex" / "config.toml"
config = tomllib.loads(config_path.read_text(encoding="utf-8"))

expected = {
    "salary_v2": "agents/salary-v2.toml",
    "time_engine": "agents/time-engine.toml",
    "mobile_platforms": "agents/mobile-platforms.toml",
    "ui_ux": "agents/ui-ux.toml",
    "team_lead": "agents/team-lead.toml",
    "qa_reviewer": "agents/qa-reviewer.toml",
    "control_gate": "agents/control-gate.toml",
    "sales_growth": "agents/sales-growth.toml",
    "customer_support": "agents/customer-support.toml",
    "marketing_comms": "agents/marketing-comms.toml",
    "people_ops": "agents/people-ops.toml",
    "finance_accounting": "agents/finance-accounting.toml",
    "legal_compliance": "agents/legal-compliance.toml",
    "incident_ops": "agents/incident-ops.toml",
    "product_manager": "agents/product-manager.toml",
    "international_lead": "agents/international-lead.toml",
}

errors = []
agents = config.get("agents", {})

for role, relative in expected.items():
    declaration = agents.get(role)
    if not isinstance(declaration, dict):
        errors.append(f"{role}: déclaration absente")
        continue
    if declaration.get("config_file") != f"./{relative}":
        errors.append(f"{role}: config_file incorrect")
        continue
    path = config_path.parent / relative
    if not path.is_file():
        errors.append(f"{role}: fichier absent")
        continue
    try:
        role_config = tomllib.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"{role}: TOML invalide: {exc}")
        continue
    if "name" in role_config or "description" in role_config:
        errors.append(f"{role}: métadonnées au mauvais niveau")
    if "default_permissions" not in role_config:
        errors.append(f"{role}: permissions non définies")
    if "developer_instructions" not in role_config:
        errors.append(f"{role}: instructions absentes")

if agents.get("max_concurrent_threads_per_session") != 5:
    errors.append("concurrence agents différente de 5")

github_mcp = config.get("mcp_servers", {}).get("github", {})
if not github_mcp:
    errors.append("GitHub MCP absent")
else:
    if github_mcp.get("enabled") is not True:
        errors.append("GitHub MCP doit être activé")
    if not str(github_mcp.get("url", "")).endswith("/readonly"):
        errors.append("GitHub MCP doit rester sur le point de terminaison readonly")
    if github_mcp.get("http_headers_helper") != "bash scripts/github-mcp-headers.sh":
        errors.append("GitHub MCP doit utiliser le helper gh local")
    if "bearer_token_env_var" in github_mcp:
        errors.append("GitHub MCP ne doit pas dépendre d'un token persistant dans la config")

firebase_mcp = config.get("mcp_servers", {}).get("firebase", {})
if not firebase_mcp:
    errors.append("Firebase MCP absent")
else:
    if firebase_mcp.get("enabled") is not True:
        errors.append("Firebase MCP doit être activé")
    if firebase_mcp.get("command") != "npx":
        errors.append("Firebase MCP doit utiliser npx")
    args = firebase_mcp.get("args", [])
    required_args = ["-y", "firebase-tools@latest", "mcp", "--dir", ".", "--only", "functions,crashlytics,apphosting,developerknowledge"]
    if args != required_args:
        errors.append("Firebase MCP: arguments inattendus")
    tools = set(firebase_mcp.get("enabled_tools", []))
    forbidden = {
        "firebase_create_project",
        "firebase_create_app",
        "firebase_create_android_sha",
        "firebase_update_environment",
        "firebase_init",
        "auth_update_user",
        "auth_set_sms_region_policy",
        "realtimedatabase_set_data",
        "remoteconfig_update_template",
        "firestore_add_document",
        "firestore_update_document",
        "firestore_delete_document",
        "firestore_create_database",
        "firestore_update_database",
        "firestore_delete_database",
        "firestore_create_index",
        "firestore_delete_index",
        "firestore_delete_backup",
        "firestore_create_backup_schedule",
        "firestore_update_backup_schedule",
        "firestore_delete_backup_schedule",
        "messaging_send_message",
        "crashlytics_create_note",
        "crashlytics_delete_note",
        "crashlytics_update_issue",
    }
    exposed_forbidden = sorted(tools & forbidden)
    if exposed_forbidden:
        errors.append(f"Firebase MCP expose des outils d'écriture: {exposed_forbidden}")

if errors:
    print("CONFIG CODEX HORATRACK: FAIL")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print(f"CONFIG CODEX HORATRACK: PASS — {len(expected)} agents câblés")
