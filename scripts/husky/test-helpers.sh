#!/usr/bin/env sh
# 셸 테스트 공용 단언 (WD-506) — `. scripts/husky/test-helpers.sh` 로 읽어 쓴다(train.test.sh 등).
# hooks.test.sh 는 인자 순서가 다른 자체 check 를 쓰므로 이 파일을 읽지 않는다.
#
#   check <이름> <실제> <있어야 할 부분 문자열> [<없어야 할 부분 문자열>]
#   finish   — 결과를 찍고 실패가 하나라도 있으면 종료 코드 1

TH_PASS=0
TH_FAIL=0

check() {
  _name=$1; _actual=$2; _want=$3; _unwant=${4:-}
  case "$_actual" in
    *"$_want"*) : ;;
    *) TH_FAIL=$((TH_FAIL + 1)); printf '✗ %s\n  기대: %s\n  실제: %s\n' "$_name" "$_want" "$_actual"; return 0 ;;
  esac
  if [ -n "$_unwant" ]; then
    case "$_actual" in
      *"$_unwant"*) TH_FAIL=$((TH_FAIL + 1)); printf '✗ %s\n  없어야 함: %s\n  실제: %s\n' "$_name" "$_unwant" "$_actual"; return 0 ;;
    esac
  fi
  TH_PASS=$((TH_PASS + 1))
}

finish() {
  echo "$(basename "$0"): 통과 $TH_PASS, 실패 $TH_FAIL"
  [ "$TH_FAIL" -eq 0 ]
}
