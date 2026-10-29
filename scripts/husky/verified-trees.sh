#!/usr/bin/env sh
# 「전체 회귀를 통과한 트리」 기록 — full-regression.sh 가 쓰고 pre-push.sh 가 읽는다 (WD-459, smart-workplace WP-446 이식).
# 기록 파일은 git common dir 에 둬 모든 워크트리가 공유한다. PREPUSH_VERIFIED_FILE 로 바꿀 수 있다(테스트용).
# 한 줄 = 트리 하나. 범위를 줄여 통과했으면 「<트리> <범위>」(gate-scope.sh), 전체로 통과했으면 트리만 쓴다.
# gate-scope.sh 를 함께 source 해야 한다(scope_covers). 내부 변수는 호출하는 쪽을 덮지 않게 _vt_ 로 시작한다.

VERIFIED_FILE="${PREPUSH_VERIFIED_FILE:-$(cd "$(git rev-parse --git-common-dir)" && pwd)/firehub-prepush-verified-trees}"

# verified_scope <트리 해시> — 그 트리가 통과한 범위(트리만 쓴 줄은 full). 기록이 없으면 빈 출력.
verified_scope() {
  [ -f "$VERIFIED_FILE" ] || return 0
  awk -v t="$1" '$1 == t { s = ($2 == "" ? "full" : $2) } END { if (s != "") print s }' "$VERIFIED_FILE"
}

# is_verified <트리 해시> [필요한 범위] — 기록된 범위가 필요한 범위(생략하면 full)를 덮으면 0.
is_verified() {
  _vt_s=$(verified_scope "$1")
  [ -n "$_vt_s" ] && scope_covers "$_vt_s" "${2:-full}"
}

# is_verified_head — 지금 HEAD 의 트리가 HEAD 에 필요한 범위(origin/main 기준)로 통과했는가.
# pre-push 가 push 때 보는 것과 같은 판정이라, 게이트를 미리 돌린 뒤 기록됐는지 확인할 때 쓴다(.work-loop/project.md).
is_verified_head() {
  if git rev-parse --verify --quiet refs/remotes/origin/main >/dev/null; then
    _vt_need=$(gate_scope refs/remotes/origin/main HEAD)
  else
    _vt_need=full
  fi
  is_verified "$(git rev-parse 'HEAD^{tree}')" "$_vt_need"
}

# record_verified <트리 해시> [범위] — 기록에 더한다(같은 트리는 한 줄, 최근 500개만).
# 이미 기록된 트리면 두 범위를 합친다. 동시 기록이 서로 덮지 않게 임시 파일은 매번 새로 만든다.
record_verified() {
  _vt_scope=${2:-full}
  _vt_old=$(verified_scope "$1")
  [ -z "$_vt_old" ] || _vt_scope=$(scope_union "$_vt_old" "$_vt_scope")
  if [ "$_vt_scope" = full ]; then _vt_entry="$1"; else _vt_entry="$1 $_vt_scope"; fi
  _vt_tmp=$(mktemp "$VERIFIED_FILE.XXXXXX")
  { awk -v t="$1" '$1 != t' "$VERIFIED_FILE" 2>/dev/null || true; echo "$_vt_entry"; } | tail -n 500 > "$_vt_tmp"
  mv "$_vt_tmp" "$VERIFIED_FILE"
}
