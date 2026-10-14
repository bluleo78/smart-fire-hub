# WD(Gen:iA Data) 이슈 트래커 — MCP 호출 레시피

`ai-driven-pilot`/`solver`/`explorer`/`agent-inspector`가 GitHub Issues 대신 쓰는 이슈 트래커. smart-workplace의
워크스페이스(genia-works.iacloud.kr)에 있는 프로젝트 중 **`WD`(Gen:iA Data) = 이 저장소(smart-fire-hub)**.
(관련: [[reference_workplace_wd_project]])

## 왜 `gh` CLI가 아니라 curl인가

`workplace` MCP 서버는 `smart-workplace` 리포의 `.claude.json`에만 설정돼 있다. **이 저장소(smart-fire-hub) 세션에는
그 MCP 도구가 로드돼 있지 않다** — pilot이 스폰하는 subagent도 마찬가지다. 그래서 모든 호출은 `~/.claude.json`의
`projects["/Users/bluleo78/git/smart-workplace"].mcpServers.workplace` 에 있는 `url`/`Authorization` 헤더를 그대로 꺼내
**raw HTTP POST(JSON-RPC, MCP 프로토콜)**로 호출한다. 토큰을 재조회하는 셸 함수:

```bash
WD_URL="https://genia-works.iacloud.kr/mcp"
WD_TOKEN=$(python3 -c "
import json
cfg = json.load(open('/Users/bluleo78/.claude.json'))
print(cfg['projects']['/Users/bluleo78/git/smart-workplace']['mcpServers']['workplace']['headers']['Authorization'].split(' ',1)[1])
")

wd_call() {  # wd_call <tool-name> '<json-arguments>'
  curl -s -X POST "$WD_URL" \
    -H "Authorization: Bearer $WD_TOKEN" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
  | python3 -c "
import sys,json
for line in sys.stdin:
    if line.startswith('data: '):
        d = json.loads(line[6:])
        print(d['result']['content'][0]['text'])
"
}
```

응답은 SSE 프레임(`event: message\ndata: {...}`)으로 온다 — `data: ` 줄만 꺼내 JSON 파싱해야 실제 결과 텍스트가 나온다(위
`wd_call` 헬퍼가 이미 처리).

## 프로젝트 / 에픽

- `projectKey`는 항상 `"WD"`.
- **기능에 안 묶이는 일반 버그**는 **`WD-10`**(버그 수정 및 이슈 대응) 아래 생성. 특정 기능 작업 중 나온 결함이면 그 기능의
  에픽(예: 데이터셋 보안 강화 = WD-17 같은 주제당 하나의 에픽) 아래. 일반 사용성 개선은 **`WD-1`**.
- 담당자(assignees)는 항상 `["dh.yang@iacloud.kr"]`.
- 에픽 조회: `wd_call list_issues '{"projectKey":"WD","topLevel":true}'` (주의 — `topLevel:true`가 기대만큼 전부를
  돌려주지 않는 사례가 있었다. 특정 에픽이 안 보이면 `get_issue_detail`로 직접 키를 찔러 확인할 것).

## 라벨이 없다 — 상태는 `status` + `priority` + 본문 메타 블록으로 표현

WD 프로젝트엔 **등록된 라벨이 0개**고, `update_issue`에 없는 라벨 문자열을 넣으면 거부된다(관리 화면에서 사전 생성한
라벨만 허용 — API로 즉석 생성 불가). GitHub Issues의 라벨 기반 게이트(`ai-fix`/`resolved`/`severity:*`/
`subagent-quality`/`needs-decision`/`needs-info`/`pilot:processing` 등)를 그대로 못 쓴다.

**대체 설계**: 본문(body) 맨 앞에 고정 포맷 메타 블록을 두고, 상태가 바뀔 때마다 그 블록만 정규식으로 치환해
`update_issue(body=...)`로 통째로 다시 쓴다. 블록 아래 `---` 구분선 밑은 원래 이슈 설명(사람이 읽는 내용)이고
건드리지 않는다.

```
## 자동화 상태
- 유형: bug
- 성격: ui | subagent-quality:accuracy | subagent-quality:tool | subagent-quality:perf | subagent-quality:ux
- 심각도: critical | major | minor | ux
- 플래그: security, a11y, design, needs-decision, needs-info, on-hold, deferred  (해당 없으면 "없음")
- 처리: ai-fix | needs-human
- 단계: open | processing | resolved | regression:N | closed:completed | closed:duplicate | closed:wontfix | closed:by-design | closed:not-reproducible | closed:invalid

---

(사람이 읽는 원래 설명 — 증상/재현/수정 방향 등)
```

필드별 의미는 [[issue-lifecycle]](`.claude/docs/issue-lifecycle.md`, WD판) 참고. 이 문서는 **호출 방법**만 다룬다.

### 메타 블록 파싱/치환 규칙

- 블록은 반드시 `## 자동화 상태`로 시작해 그다음에 나오는 첫 `\n---\n`까지다.
- 한 필드를 바꾸려면: `get_issue_detail`로 `body` 전체를 받아 → 정규식으로 해당 줄만 치환 → `update_issue(body=전체)`로
  **본문 전체**를 다시 보낸다(라벨처럼 부분 add/remove가 안 된다. 통째로 재작성).
- 필드 하나짜리 치환 예시(Python, 쉘 안에서):
  ```python
  import re
  def set_field(body: str, field: str, value: str) -> str:
      pattern = rf'(?m)^- {re.escape(field)}: .*$'
      return re.sub(pattern, f'- {field}: {value}', body, count=1)
  ```

### 실제 `status` 필드와의 매핑

WD `status`는 `TODO`/`IN_PROGRESS`/`DONE`/`CANCELED` 4종뿐이다. 본문 메타의 "단계"와 별개로, 눈에 보이는 보드/목록
필터링을 위해 `status`도 같이 맞춘다:

| 본문 "단계" | WD `status` |
|---|---|
| `open`(사람 큐 대기, `on-hold`/`deferred`/`needs-decision`/`needs-info` 포함) | `TODO` |
| `processing`(pilot이 돌리는 중) | `IN_PROGRESS` |
| `resolved`, `regression:N`(크로스체크 대기/회귀) | `IN_PROGRESS` |
| `closed:completed` | `DONE` |
| `closed:duplicate`/`wontfix`/`by-design`/`not-reproducible`/`invalid` | `CANCELED` |

GitHub Projects의 별도 보드(backlog/in_progress/in_review/done) + iteration sweep은 **WD에선 없다** — WD 이슈 자체의
`status` 필드가 그 역할을 겸한다. `add-to-board.sh`/`board-status.sh`/`sweep-iteration.sh` 스크립트는 WD 전환본에서
쓰지 않는다(각 스킬에서 직접 `update_issue(status=...)` 호출로 대체).

## 명령 대응표

| GitHub(`gh`) | WD(`wd_call`) |
|---|---|
| `gh issue create --title T --body B --label "bug,severity:major"` | `wd_call create_issue '{"projectKey":"WD","parent":"WD-10","title":"T","body":"<메타블록 포함 B>","type":"BUG","assignees":["dh.yang@iacloud.kr"]}'` |
| `gh issue list --state open --label "severity:critical"` | `wd_call list_issues '{"projectKey":"WD","status":"TODO,IN_PROGRESS","q":"심각도: critical"}'` (q는 본문 전문검색 — 확인됨) |
| `gh issue view N --json labels,body` | `wd_call get_issue_detail '{"issueKey":"WD-N"}'` |
| `gh issue edit N --add-label X --remove-label Y` | body 메타 블록의 해당 필드 값을 치환 후 `wd_call update_issue '{"issueKey":"WD-N","body":"..."}'` |
| `gh issue comment N --body "..."` | `wd_call add_comment '{"issueKey":"WD-N","body":"..."}'` |
| `gh issue close N --reason completed` | 메타 "단계"를 `closed:completed`로 치환 + `wd_call update_issue '{"issueKey":"WD-N","body":"...","status":"DONE"}'` |
| 회귀 횟수 카운트(코멘트에서 "🔴 회귀 발견" 검색) | **그대로 유지** — `get_issue_detail(allComments:true)`가 댓글 전체를 돌려주므로 동일하게 텍스트 매칭 |

## 생성 예시 (풀)

```bash
wd_call create_issue "$(python3 -c '
import json
body = """## 자동화 상태
- 유형: bug
- 성격: ui
- 심각도: major
- 플래그: 없음
- 처리: ai-fix
- 단계: open

---

증상: ...
재현: ...
"""
print(json.dumps({
  "projectKey":"WD","parent":"WD-10",
  "title":"짧은 증상 서술문",
  "body":body,"type":"BUG","priority":"HIGH",
  "assignees":["dh.yang@iacloud.kr"]
}))
')"
```

## 확인 안 된 가정 (쓰기 전에 실측 권장)

- `list_issues`의 `q`가 body 전문검색임은 1건(`"bedrock-mantle"` → WD-50 단독 매치)으로만 확인했다. AND/OR 조합,
  대소문자, 형태소 분석 여부는 미확인 — 쿼리가 기대와 다르게 걸리면 `status`만으로 좁힌 뒤 `get_issue_detail`을
  순회해 본문을 직접 grep하는 폴백을 쓴다(WD 프로젝트는 현재 50여 건이라 전수 순회도 비용이 크지 않다).
- `topLevel:true`가 모든 최상위 에픽을 안 돌려준 사례가 있었다(WD-10/WD-1이 리스트에 안 잡혔는데 `get_issue_detail`로는
  존재 확인됨) — 에픽 키를 찾을 땐 이 리스트만 믿지 말 것.
