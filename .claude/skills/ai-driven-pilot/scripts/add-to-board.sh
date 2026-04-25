#!/usr/bin/env bash
# 이슈를 GitHub Projects 보드에 추가하고 현재 iteration + Status=ready를 배정한다
# 사용법: add-to-board.sh <이슈번호>
# - 이미 보드에 있으면 iteration/status만 갱신 (idempotent)

ISSUE_NUM=$1

GH_PROJECT_ID="PVT_kwHOAE-_Hc4BUFv9"
GH_STATUS_FIELD="PVTSSF_lAHOAE-_Hc4BUFv9zhBQQq0"
GH_ITERATION_FIELD="PVTIF_lAHOAE-_Hc4BUFv9zhBQRMI"
GH_OPT_READY="e18bf179"

# 이슈 node ID 조회 (addProjectV2ItemByContentId에 필요)
ISSUE_NODE_ID=$(gh api graphql -f query="{
  repository(owner:\"bluleo78\", name:\"smart-fire-hub\") {
    issue(number: $ISSUE_NUM) { id }
  }
}" -q '.data.repository.issue.id')

[ -z "$ISSUE_NODE_ID" ] && { echo "[add-to-board] ❌ 이슈 #$ISSUE_NUM 조회 실패" >&2; exit 1; }

# 프로젝트에 추가 (이미 있으면 기존 item ID 반환 — idempotent)
ITEM_ID=$(gh api graphql -f query="mutation {
  addProjectV2ItemByContentId(input: {
    projectId: \"$GH_PROJECT_ID\"
    contentId: \"$ISSUE_NODE_ID\"
  }) { item { id } }
}" -q '.data.addProjectV2ItemByContentId.item.id')

[ -z "$ITEM_ID" ] && { echo "[add-to-board] ❌ 보드 추가 실패 #$ISSUE_NUM" >&2; exit 1; }

# 현재 iteration ID 동적 조회 — startDate <= 오늘인 것 중 가장 최근
TODAY=$(date +%Y-%m-%d)
ITER_ID=$(gh api graphql -f query="{
  viewer {
    projectsV2(first: 1) {
      nodes {
        fields(first: 20) {
          nodes {
            ... on ProjectV2IterationField {
              configuration { iterations { id startDate } }
            }
          }
        }
      }
    }
  }
}" -q "[.data.viewer.projectsV2.nodes[0].fields.nodes[] | select(.configuration?) | .configuration.iterations[] | select(.startDate <= \"$TODAY\")] | last | .id")

if [ -n "$ITER_ID" ]; then
  gh api graphql -f query="mutation {
    updateProjectV2ItemFieldValue(input: {
      projectId: \"$GH_PROJECT_ID\"
      itemId: \"$ITEM_ID\"
      fieldId: \"$GH_ITERATION_FIELD\"
      value: { iterationId: \"$ITER_ID\" }
    }) { projectV2Item { id } }
  }" > /dev/null || echo "[add-to-board] ⚠️  #$ISSUE_NUM iteration 배정 실패" >&2
fi

# Status = ready (파일럿이 픽업 예정)
gh api graphql -f query="mutation {
  updateProjectV2ItemFieldValue(input: {
    projectId: \"$GH_PROJECT_ID\"
    itemId: \"$ITEM_ID\"
    fieldId: \"$GH_STATUS_FIELD\"
    value: { singleSelectOptionId: \"$GH_OPT_READY\" }
  }) { projectV2Item { id } }
}" > /dev/null || echo "[add-to-board] ⚠️  #$ISSUE_NUM status=ready 배정 실패" >&2

echo "[add-to-board] ✅ #$ISSUE_NUM → 보드 배정 완료 (iteration=$ITER_ID, status=ready)"
