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

if errors:
    print("CONFIG CODEX HORATRACK: FAIL")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print(f"CONFIG CODEX HORATRACK: PASS — {len(expected)} agents câblés")
