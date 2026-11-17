#!/usr/bin/env sh
# 무거운 테스트 게이트(E2E·gradle) 저장소 전역 직렬화 헬퍼 — pre-commit/pre-push 가 source 한다 (WD-257, smart-workplace 이식).
# - 여러 워크트리·세션이 동시에 게이트를 돌리면 CPU 가 초과 구독돼 타임아웃성 flaky 와 시스템 전체 부하를 낳는다.
#   Playwright globalSetup 락(e2e/global-setup.ts)은 webServer 기동·preview 빌드보다 늦게 잡히고 gradle 은 덮지 못하므로,
#   게이트 단계 전체를 여기서 한 번 더 감싼다.
# - 락 파일은 git common dir 에 둬 모든 워크트리가 같은 락을 공유한다.
# - macOS 는 flock(1) 이 기본 미탑재 — 실제 실행 직전에만 확인한다(brew install flock).

TEST_GATE_LOCK="$(cd "$(git rev-parse --git-common-dir)" && pwd)/firehub-test-gate.lock"

# run_locked <명령...> — 락을 잡은 채 명령을 실행하고, 다른 세션이 잡고 있으면 풀릴 때까지 대기한다.
run_locked() {
  command -v flock >/dev/null 2>&1 || {
    echo "[test-lock] flock 명령이 없습니다. 'brew install flock' 후 다시 시도하세요." >&2
    exit 1
  }
  # macOS 는 유휴 잠자기로 30초짜리 테스트가 250~495초 걸려 게이트가 타임아웃으로 실패했다 (WD-764).
  # caffeinate 로 유휴 잠자기를 막되, 명령을 감싸지 않고 훅 셸($$)을 지켜보는 백그라운드로 띄운다.
  #  - -w $$: 훅 셸이 끝나면 caffeinate 도 스스로 끝난다(따로 정리할 필요 없음). 그래서 run_locked 를 여러 번 불러
  #    caffeinate 가 여럿 떠도 모두 훅 셸과 함께 끝나 해가 없다.
  #  - 명령의 종료 코드가 caffeinate 를 거치지 않는다 — 아래 flock 이 명령 자신의 코드를 그대로 돌려준다.
  #  - 락을 얻기 전에 띄워 다른 세션을 기다리는 시간도 잠자기에서 지킨다.
  # 리눅스·파드·CI 는 caffeinate 가 없어 아무 일도 하지 않는다. 잠자기 방지 assertion 은 프로세스별이라 다른 세션과 간섭하지 않는다.
  if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -i -w $$ >/dev/null 2>&1 &
  fi
  # 즉시 획득 못 하면 대기 중임을 알린다(조용히 멈춘 것처럼 보이지 않게).
  if ! flock -n "$TEST_GATE_LOCK" true; then
    echo "[test-lock] 다른 세션이 테스트 게이트 실행 중 — 대기합니다 (락: $TEST_GATE_LOCK)"
  fi
  flock "$TEST_GATE_LOCK" "$@"
}
