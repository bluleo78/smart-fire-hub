# 컨텍스트 컴팩션 후 세션 재개

이전 대화가 압축되어 재시작된 경우 다음 순서로 상태를 복구한다.

## 1) playwright-cli 세션 생존 확인

```bash
playwright-cli -s=<SESSION명> snapshot --depth=2 2>&1 | head -5
# → TimeoutError 또는 오류 시 세션 닫고 재시작:
# playwright-cli -s=<SESSION명> close 2>/dev/null
# SESSION="explorer#$(openssl rand -hex 3)"
```

## 2) 커버리지 매트릭스에서 마지막 상태 파악

진행 중이던 perspective의 매트릭스 파일을 본다 (`bug`/`design`/`a11y`/`perf`). 알 수 없으면 모든 매트릭스를 짧게 훑는다.

```bash
# perspective가 명확할 때 (예: bug)
PERSP=bug
grep "🔴\|✅\|⬜" test-results/exploratory/.coverage-matrix-${PERSP}.md | wc -l
grep "⬜" test-results/exploratory/.coverage-matrix-${PERSP}.md | head -10

# perspective 불명일 때 — 어느 매트릭스가 진행 중인지 보기
ls -la test-results/exploratory/.coverage-matrix-*.md
grep -l "🔄" test-results/exploratory/.coverage-matrix-*.md  # 진행 중 표식 있는 파일
```

## 3) 마지막 버그 번호 확인

```bash
gh issue list --state all --limit 5 --json number,title
```

재개 후에는 ⬜(미시작) 항목부터 이어서 진행한다.
