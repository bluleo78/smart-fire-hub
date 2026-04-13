#!/bin/bash
# continuous-task.sh — task-start를 반복 실행하는 외부 스크립트
#
# 매 회차마다 claude -p로 독립 프로세스를 실행하므로:
# - 메인 + 팀 컨텍스트가 100% 초기화됨
# - 코드/로드맵 상태가 변했으므로 같은 지시라도 다른 작업 수행
#
# 사용법:
#   ./scripts/continuous-task.sh "로드맵 다음 항목 진행해줘"
#   ./scripts/continuous-task.sh "로드맵 다음 항목 진행해줘" 5
#   ./scripts/continuous-task.sh "디자인 시스템 위반 수정해줘" 3 --skip-analysis
#
# 종료: Ctrl+C

set -euo pipefail

# 인자 파싱
INSTRUCTION="${1:?사용법: $0 \"업무 지시\" [최대 회차] [--auto 등 추가 플래그]}"
MAX_ROUNDS="${2:-10}"
EXTRA_FLAGS="${3:-}"

# 상태 파일 경로
STATUS_FILE=".omc/task-result.json"

# 색상
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# 로그 디렉토리
LOG_DIR=".omc/logs/continuous"
mkdir -p "$LOG_DIR"

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN} 연속 실행 모드${NC}"
echo -e "${CYAN}========================================${NC}"
echo -e " 지시: ${GREEN}${INSTRUCTION}${NC}"
echo -e " 최대 회차: ${MAX_ROUNDS}"
echo -e " 추가 플래그: ${EXTRA_FLAGS:-없음}"
echo -e " 종료: Ctrl+C"
echo -e "${CYAN}========================================${NC}"
echo ""

COMPLETED=0

for i in $(seq 1 "$MAX_ROUNDS"); do
  echo -e "${CYAN}=== 회차 ${i}/${MAX_ROUNDS} ===${NC}"
  echo -e " 시작: $(date '+%Y-%m-%d %H:%M:%S')"

  # 이전 상태 파일 삭제
  rm -f "$STATUS_FILE"

  # 로그 파일
  LOG_FILE="${LOG_DIR}/round-${i}-$(date '+%Y%m%d-%H%M%S').log"

  # Claude 실행
  # task-start 스킬에 --auto 플래그를 전달하여 체크포인트 없이 진행
  CLAUDE_CODE_ENTRYPOINT= CLAUDECODE= claude -p \
    "/task-start --auto ${EXTRA_FLAGS} ${INSTRUCTION}" \
    --permission-mode bypassPermissions \
    --model opus \
    2>&1 | tee "$LOG_FILE"

  EXIT_CODE=${PIPESTATUS[0]}
  echo ""

  # 종료 코드 확인
  if [ "$EXIT_CODE" -ne 0 ]; then
    echo -e "${RED}비정상 종료 (exit code: ${EXIT_CODE}). 루프 중단.${NC}"
    break
  fi

  # 상태 파일 확인
  if [ -f "$STATUS_FILE" ]; then
    STATUS=$(python3 -c "import json; print(json.load(open('${STATUS_FILE}'))['status'])" 2>/dev/null || echo "unknown")

    case $STATUS in
      "no_more_tasks")
        echo -e "${GREEN}더 이상 수행할 작업 없음. 루프 종료.${NC}"
        COMPLETED=$i
        break
        ;;
      "failed"|"blocked")
        echo -e "${RED}작업 실패/블로커 발생. 루프 중단.${NC}"
        break
        ;;
      "completed")
        echo -e "${GREEN}회차 ${i} 완료.${NC}"
        COMPLETED=$i
        ;;
      *)
        echo -e "${YELLOW}알 수 없는 상태: ${STATUS}. 계속 진행.${NC}"
        COMPLETED=$i
        ;;
    esac
  else
    # 상태 파일 없어도 정상 종료(exit 0)면 완료로 간주
    echo -e "${YELLOW}상태 파일 없음. 정상 종료로 간주하고 계속 진행.${NC}"
    COMPLETED=$i
  fi

  # 다음 회차 대기
  if [ "$i" -lt "$MAX_ROUNDS" ]; then
    echo -e "${CYAN}5초 후 다음 회차 시작... (Ctrl+C로 종료)${NC}"
    sleep 5
  fi
done

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN} 연속 실행 종료${NC}"
echo -e "${CYAN}========================================${NC}"
echo -e " 완료 회차: ${COMPLETED}/${MAX_ROUNDS}"
echo -e " 종료 시각: $(date '+%Y-%m-%d %H:%M:%S')"
echo -e " 로그: ${LOG_DIR}/"
echo -e "${CYAN}========================================${NC}"
