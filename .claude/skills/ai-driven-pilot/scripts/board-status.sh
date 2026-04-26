#!/usr/bin/env bash
# GitHub Projects v2 보드 Status 필드 업데이트 헬퍼
# 사용법: board-status.sh <이슈번호> <상태>
# 상태값: backlog | ready | in_progress | in_review | done

ISSUE_NUM=$1
STATUS_KEY=$2

# 프로젝트 상수 (My Smart Fire Hub)
GH_PROJECT_ID="PVT_kwHOAE-_Hc4BUFv9"
GH_STATUS_FIELD="PVTSSF_lAHOAE-_Hc4BUFv9zhBQQq0"
GH_OPT_BACKLOG="f75ad846"
GH_OPT_READY="e18bf179"
GH_OPT_IN_PROGRESS="47fc9ee4"
GH_OPT_IN_REVIEW="aba860b9"
GH_OPT_DONE="98236657"

case "$STATUS_KEY" in
  backlog)     OPT_ID="$GH_OPT_BACKLOG" ;;
  ready)       OPT_ID="$GH_OPT_READY" ;;
  in_progress) OPT_ID="$GH_OPT_IN_PROGRESS" ;;
  in_review)   OPT_ID="$GH_OPT_IN_REVIEW" ;;
  done)        OPT_ID="$GH_OPT_DONE" ;;
  *) echo "Unknown status: $STATUS_KEY" >&2; exit 1 ;;
esac

# 이슈 번호 → project item ID 조회
ITEM_ID=$(gh api graphql -f query="{
  repository(owner:\"bluleo78\", name:\"smart-fire-hub\") {
    issue(number: $ISSUE_NUM) {
      projectItems(first: 1) { nodes { id } }
    }
  }
}" -q '.data.repository.issue.projectItems.nodes[0].id' 2>/dev/null)

# 이슈가 보드에 없으면 조용히 스킵
[ -z "$ITEM_ID" ] || [ "$ITEM_ID" = "null" ] && exit 0

if ! gh api graphql -f query="mutation {
  updateProjectV2ItemFieldValue(input: {
    projectId: \"$GH_PROJECT_ID\"
    itemId: \"$ITEM_ID\"
    fieldId: \"$GH_STATUS_FIELD\"
    value: { singleSelectOptionId: \"$OPT_ID\" }
  }) { projectV2Item { id } }
}" > /dev/null; then
  echo "[board-status] ⚠️  #$ISSUE_NUM 보드 상태 업데이트 실패 (status=$STATUS_KEY) — 파일럿 흐름은 계속" >&2
fi
