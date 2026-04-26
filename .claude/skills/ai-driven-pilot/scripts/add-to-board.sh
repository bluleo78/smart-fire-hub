#!/usr/bin/env bash
# 이슈를 GitHub Projects 보드에 추가하고 현재 iteration + Status=ready를 배정한다
# 사용법: add-to-board.sh <이슈번호>
# - 이미 보드에 있으면 iteration/status만 갱신 (idempotent)

ISSUE_NUM=$1

GH_PROJECT_ID="PVT_kwHOAE-_Hc4BUFv9"
GH_STATUS_FIELD="PVTSSF_lAHOAE-_Hc4BUFv9zhBQQq0"
GH_ITERATION_FIELD="PVTIF_lAHOAE-_Hc4BUFv9zhBQRMI"
GH_OPT_READY="e18bf179"

# 이슈 node ID 조회
ISSUE_NODE_ID=$(gh api graphql -f query="{
  repository(owner:\"bluleo78\", name:\"smart-fire-hub\") {
    issue(number: $ISSUE_NUM) { id }
  }
}" -q '.data.repository.issue.id' 2>/dev/null)

[ -z "$ISSUE_NODE_ID" ] || [ "$ISSUE_NODE_ID" = "null" ] && { echo "[add-to-board] ❌ 이슈 #$ISSUE_NUM 조회 실패" >&2; exit 1; }

# 프로젝트에 추가 — addProjectV2ItemByContentId는 deprecated, addProjectV2ItemById 사용
ITEM_ID=$(gh api graphql -f query="mutation {
  addProjectV2ItemById(input: {
    projectId: \"$GH_PROJECT_ID\"
    contentId: \"$ISSUE_NODE_ID\"
  }) { item { id } }
}" -q '.data.addProjectV2ItemById.item.id' 2>/dev/null)

[ -z "$ITEM_ID" ] || [ "$ITEM_ID" = "null" ] && { echo "[add-to-board] ❌ 보드 추가 실패 #$ISSUE_NUM" >&2; exit 1; }

# 현재 iteration ID 조회 — project ID로 직접 조회 (viewer.projectsV2는 순서 비결정적)
TODAY=$(date +%Y-%m-%d)
ITER_JSON=$(gh api graphql -f query="{
  node(id: \"$GH_PROJECT_ID\") {
    ... on ProjectV2 {
      fields(first: 20) {
        nodes {
          ... on ProjectV2IterationField {
            configuration { iterations { id startDate } }
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

if [ -n "$ITER_ID" ] && [ "$ITER_ID" != "null" ]; then
  gh api graphql -f query="mutation {
    updateProjectV2ItemFieldValue(input: {
      projectId: \"$GH_PROJECT_ID\"
      itemId: \"$ITEM_ID\"
      fieldId: \"$GH_ITERATION_FIELD\"
      value: { iterationId: \"$ITER_ID\" }
    }) { projectV2Item { id } }
  }" > /dev/null 2>&1 || echo "[add-to-board] ⚠️  #$ISSUE_NUM iteration 배정 실패" >&2
else
  echo "[add-to-board] ⚠️  #$ISSUE_NUM iteration 조회 실패" >&2
fi

# Status = ready (파일럿이 픽업 예정)
gh api graphql -f query="mutation {
  updateProjectV2ItemFieldValue(input: {
    projectId: \"$GH_PROJECT_ID\"
    itemId: \"$ITEM_ID\"
    fieldId: \"$GH_STATUS_FIELD\"
    value: { singleSelectOptionId: \"$GH_OPT_READY\" }
  }) { projectV2Item { id } }
}" > /dev/null 2>&1 || echo "[add-to-board] ⚠️  #$ISSUE_NUM status=ready 배정 실패" >&2

echo "[add-to-board] ✅ #$ISSUE_NUM → 보드 배정 완료 (iteration=$ITER_ID, status=ready)"
