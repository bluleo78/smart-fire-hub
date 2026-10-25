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
  # 즉시 획득 못 하면 대기 중임을 알린다(조용히 멈춘 것처럼 보이지 않게).
  if ! flock -n "$TEST_GATE_LOCK" true; then
    echo "[test-lock] 다른 세션이 테스트 게이트 실행 중 — 대기합니다 (락: $TEST_GATE_LOCK)"
  fi
  flock "$TEST_GATE_LOCK" "$@"
}
