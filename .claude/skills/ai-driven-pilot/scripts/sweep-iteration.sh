#!/usr/bin/env bash
# 모든 open 이슈의 board iteration을 현재 iteration으로 이관한다 (status는 보존).
# 사용법: sweep-iteration.sh
# - GitHub Projects v2는 iteration roll-over를 자동 수행하지 않으므로, 새 iteration 시작 후 미완료 이슈가 옛/없음 상태로 남는다.
# - 이 스크립트는 보드의 모든 open 이슈를 순회하며 iteration field만 갱신한다.
# - status는 건드리지 않는다 — add-to-board.sh와의 핵심 차이.

set -u

GH_PROJECT_ID="PVT_kwHOAE-_Hc4BUFv9"
GH_ITERATION_FIELD="PVTIF_lAHOAE-_Hc4BUFv9zhBQRMI"

# 현재 iteration ID 조회 — today 이전 startDate 중 가장 최근
TODAY=$(date +%Y-%m-%d)
ITER_JSON=$(gh api graphql -f query="{
  node(id: \"$GH_PROJECT_ID\") {
    ... on ProjectV2 {
      fields(first: 20) {
        nodes {
          ... on ProjectV2IterationField {
            configuration { iterations { id title startDate } }
          }
        }
      }
    }
  }
}" 2>/dev/null)

ITER_ID=$(echo "$ITER_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
fields = d['data']['node']['fields']['nodes']
iters = []
for f in fields:
    if 'configuration' in f:
        iters = f['configuration']['iterations']
        break
today = '$TODAY'
valid = [i for i in iters if i['startDate'] <= today]
print(valid[-1]['id'] if valid else '')
" 2>/dev/null)

if [ -z "$ITER_ID" ] || [ "$ITER_ID" = "null" ]; then
  echo "[sweep-iteration] ❌ 현재 iteration 조회 실패" >&2
  exit 1
fi

ITER_TITLE=$(echo "$ITER_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
fields = d['data']['node']['fields']['nodes']
iters = []
for f in fields:
    if 'configuration' in f:
        iters = f['configuration']['iterations']
        break
today = '$TODAY'
valid = [i for i in iters if i['startDate'] <= today]
print(valid[-1].get('title', '?') if valid else '?')
")

echo "[sweep-iteration] 현재 iteration: $ITER_TITLE ($ITER_ID)"

# open 이슈 목록 — gh CLI로 받아옴
OPEN_ISSUES=$(gh issue list --state open --limit 200 --json number -q '.[].number' 2>/dev/null)
[ -z "$OPEN_ISSUES" ] && { echo "[sweep-iteration] open 이슈 없음 — 종료"; exit 0; }

UPDATED=0
SKIPPED=0
FAILED=0

for N in $OPEN_ISSUES; do
  # 이슈의 board item + 현재 iteration title 조회
  RES=$(gh api graphql -f query="{
    repository(owner:\"bluleo78\", name:\"smart-fire-hub\") {
      issue(number: $N) {
        projectItems(first: 5) {
          nodes {
            id
            project { id }
            fieldValues(first: 10) {
              nodes {
                __typename
                ... on ProjectV2ItemFieldIterationValue { title }
              }
            }
          }
        }
      }
    }
  }" 2>/dev/null)

  # 우리 보드의 item만 추출
  ITEM_ID=$(echo "$RES" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    for node in d['data']['repository']['issue']['projectItems']['nodes']:
        if node['project']['id'] == '$GH_PROJECT_ID':
            print(node['id'])
            break
except Exception:
    pass
" 2>/dev/null)

  if [ -z "$ITEM_ID" ]; then
    # 보드에 없으면 스킵 (add-to-board가 책임지는 영역)
    SKIPPED=$((SKIPPED+1))
    continue
  fi

  CUR_ITER=$(echo "$RES" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    for node in d['data']['repository']['issue']['projectItems']['nodes']:
        if node['project']['id'] == '$GH_PROJECT_ID':
            for fv in node['fieldValues']['nodes']:
                if fv.get('__typename') == 'ProjectV2ItemFieldIterationValue':
                    print(fv.get('title', ''))
                    break
            break
except Exception:
    pass
" 2>/dev/null)

  if [ "$CUR_ITER" = "$ITER_TITLE" ]; then
    # 이미 현재 iteration — 스킵
    SKIPPED=$((SKIPPED+1))
    continue
  fi

  # iteration field만 갱신 — status 건드리지 않음
  if gh api graphql -f query="mutation {
    updateProjectV2ItemFieldValue(input: {
      projectId: \"$GH_PROJECT_ID\"
      itemId: \"$ITEM_ID\"
      fieldId: \"$GH_ITERATION_FIELD\"
      value: { iterationId: \"$ITER_ID\" }
    }) { projectV2Item { id } }
  }" > /dev/null 2>&1; then
    echo "[sweep-iteration] ✅ #$N: ${CUR_ITER:-none} → $ITER_TITLE"
    UPDATED=$((UPDATED+1))
  else
    echo "[sweep-iteration] ⚠️  #$N 갱신 실패" >&2
    FAILED=$((FAILED+1))
  fi
done

echo "[sweep-iteration] 완료 — 갱신=$UPDATED, 스킵=$SKIPPED, 실패=$FAILED"
