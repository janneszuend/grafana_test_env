#!/bin/bash
set -euo pipefail

# ── Konfiguration ────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../.env"



TEAM_NAME="Application Team"   # ← hier deinen Team-Namen eintragen
ROLE_NAME="custom:appteam:explore_drilldown_apm"
ROLE_DISPLAY="App Team – Explore, Drilldown, App Observability"

H_AUTH=(-H "Authorization: Bearer ${GRAFANA_TOKEN}")
H_JSON=(-H "Content-Type: application/json")

# ── 1. Team-ID per Name suchen ───────────────────────────────
TEAM_ID=$(curl -s -G "${GRAFANA_URL}/api/teams/search" \
  "${H_AUTH[@]}" \
  --data-urlencode "name=${TEAM_NAME}" \
  | jq -r '.teams[0].id // empty')

if [[ -z "${TEAM_ID}" ]]; then
  echo "❌ Team '${TEAM_NAME}' nicht gefunden."
  exit 1
fi
echo "✅ Team gefunden: '${TEAM_NAME}' (ID: ${TEAM_ID})"

# ── 2. Prüfen ob Custom Role schon existiert ─────────────────
EXISTING_UID=$(curl -s -X GET "${GRAFANA_URL}/api/access-control/roles" \
  "${H_AUTH[@]}" \
  | jq -r ".[] | select(.name == \"${ROLE_NAME}\") | .uid")

if [[ -n "${EXISTING_UID}" ]]; then
  echo "ℹ️  Rolle '${ROLE_NAME}' existiert bereits (UID: ${EXISTING_UID})."
  echo "    Wenn du sie aktualisieren willst: Script per PUT auf /api/access-control/roles/${EXISTING_UID} anpassen (version hochzählen!)"
  ROLE_UID="${EXISTING_UID}"
else
  # ── 3. Custom Role anlegen ─────────────────────────────────
  ROLE_UID=$(curl -s -X POST "${GRAFANA_URL}/api/access-control/roles" \
    "${H_AUTH[@]}" "${H_JSON[@]}" \
    -d "{
      \"name\": \"${ROLE_NAME}\",
      \"displayName\": \"${ROLE_DISPLAY}\",
      \"description\": \"Zugang nur zu Explore, Drilldown-Apps und Application Observability\",
      \"version\": 1,
      \"global\": true,
      \"permissions\": [
        { \"action\": \"datasources:explore\" },
        { \"action\": \"datasources:read\",    \"scope\": \"datasources:*\" },
        { \"action\": \"datasources:query\",   \"scope\": \"datasources:*\" },
        { \"action\": \"datasources.id:read\", \"scope\": \"datasources:*\" },

        { \"action\": \"folders:read\",        \"scope\": \"folders:*\" },
        { \"action\": \"dashboards:read\",     \"scope\": \"dashboards:*\" },
        { \"action\": \"annotations:read\",    \"scope\": \"annotations:*\" },

        { \"action\": \"plugins.app:access\", \"scope\": \"plugins:id:grafana-metricsdrilldown-app\" },
        { \"action\": \"plugins.app:access\", \"scope\": \"plugins:id:grafana-lokiexplore-app\" },
        { \"action\": \"plugins.app:access\", \"scope\": \"plugins:id:grafana-exploretraces-app\" },
        { \"action\": \"plugins.app:access\", \"scope\": \"plugins:id:grafana-pyroscope-app\" },
        { \"action\": \"plugins.app:access\", \"scope\": \"plugins:id:grafana-app-observability-app\" },

        { \"action\": \"orgs:read\" }
      ]
    }" | jq -r '.uid')
  echo "✅ Custom Role erstellt: ${ROLE_UID}"
fi

# ── 4. Rolle dem Team zuweisen ───────────────────────────────
ASSIGN_RESULT=$(curl -s -w "\n%{http_code}" -X POST \
  "${GRAFANA_URL}/api/access-control/teams/${TEAM_ID}/roles" \
  "${H_AUTH[@]}" "${H_JSON[@]}" \
  -d "{\"roleUid\": \"${ROLE_UID}\"}")

HTTP_CODE=$(echo "${ASSIGN_RESULT}" | tail -n1)
if [[ "${HTTP_CODE}" == "200" ]]; then
  echo "✅ Rolle ${ROLE_UID} dem Team '${TEAM_NAME}' (ID ${TEAM_ID}) zugewiesen"
else
  echo "⚠️  Zuweisung möglicherweise schon vorhanden (HTTP ${HTTP_CODE})"
fi

echo ""
echo "⚠️  Damit Mitglieder NUR diese Features sehen, müssen sie Basic Role 'None' haben."