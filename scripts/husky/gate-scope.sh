#!/usr/bin/env sh
# push 게이트 범위 판정 (WD-459, smart-workplace WP-1208 이식) — full-regression.sh·pre-push.sh 가 source 해 같은 규칙을 쓴다.
# 웹 E2E 는 page.route() 로 API 를 모킹해 api 를 띄우지 않으므로, firehub-web 만 바꾼 변경은 api gradle 을 건너뛴다.
# 범위 값: full | web. 판정할 수 없으면(기준 ref 가 없음·코드 변경 없음) full 이다 — 애매하면 전체를 도는 쪽으로 기운다.
# noncode.sh 를 먼저 source 해야 한다(code_files).
# 이 파일의 함수는 호출하는 쪽 변수를 덮지 않게 내부 변수를 _gs_ 로 시작한다(sh 에는 local 이 없다).

# gate_scope <base> <commit> — base 와 갈라진 뒤 commit 이 바꾼 코드 파일로 범위를 낸다.
# base 와 commit 의 merge-base 부터 본다 — main 이 앞서 있어도 브랜치가 바꾼 것만 센다.
gate_scope() {
  _gs_mb=$(git merge-base "$1" "$2" 2>/dev/null) || { echo full; return; }
  _gs_files=$(code_files "$(git -c core.quotePath=false diff --name-only --no-renames "$_gs_mb" "$2")")
  [ -n "$_gs_files" ] || { echo full; return; }
  # firehub-web 밖 경로(api·다른 앱·scripts/·루트 설정·lockfile)가 하나라도 있으면 full.
  if printf '%s\n' "$_gs_files" | grep -qvE '^apps/firehub-web/'; then echo full; return; fi
  echo web
}

# scope_covers <기록된 범위> <필요한 범위> — 기록된 범위로 통과한 게이트가 필요한 범위를 덮으면 0.
# full 은 모두를 덮고, web 은 web 만 덮는다.
scope_covers() {
  [ "$1" = full ] && return 0
  [ "$1" = "$2" ]
}

# scope_union <범위>… — 여러 범위를 모두 덮는 가장 좁은 범위(기록 합치기).
scope_union() {
  for _gs_s in "$@"; do
    [ "$_gs_s" = web ] || { echo full; return; }
  done
  echo web
}
