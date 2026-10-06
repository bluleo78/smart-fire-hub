# Gen:iA Data 제품 소개 자료

고객사에 전달하는 화면 중심 제품 소개 자료(16:9, 12장)를 **현재 코드로 다시 만드는 방법**을 담은 문서입니다. 화면이 바뀌면 이 절차로 다시 촬영하고 PDF를 새로 만듭니다. 형제 제품 smart-workplace(`docs/intro/`)와 같은 방식입니다.

핵심 메시지: **조직 인프라(프라이빗 온프레미스) 안에서 AI로 데이터를 관리하고 분석하는 플랫폼.**

## 산출물

| 무엇 | 위치 | 비고 |
| --- | --- | --- |
| 화면 원본 | `docs/intro/deck/shots/*.png` | 데스크톱 2880×1620 |
| 표시 좌표 | `docs/intro/deck/shots/boxes.js` | 촬영 때 기록한 요소 위치(원본 px). 번호 표식·잘라 보기가 이 값을 쓴다 |
| 슬라이드 원본 | `docs/intro/deck/index.html` · `deck.css` · `deck.js` | 화면 원본을 잘라 보여 줄 뿐 가공하지 않는다 |
| PDF | `docs/intro/genia-data-intro.pdf` | 배포본. `build-pdf.mjs` 는 `dist/intro/` 에 만들고, 확정한 판만 이 위치로 복사해 커밋한다 |
| 장별 PNG | `dist/intro/png/slide-01~12.png` | 빌드 산출물(커밋하지 않음) |

## 구성(12장)

| # | 헤드라인 | 화면 |
| --- | --- | --- |
| 1 | 데이터는 조직 안에서, 분석은 AI와 함께 | 대시보드 |
| 2 | 흩어진 데이터를 한 곳에 | 데이터셋 목록 + 데이터셋 유형 선택 |
| 3 | 구조와 품질, 위치까지 한눈에 | 데이터셋 필드(빈 값·고유값) + 지도 탭 |
| 4 | 계약서·보고서도 검색되는 데이터로 | 문서 데이터셋(업로드·목록) + 문서 검색 |
| 5 | 말로 물으면, AI가 데이터로 답합니다 | AI 어시스턴트(조회 단계·차트·해석) |
| 6 | 숫자와 문서를 함께 읽고 답합니다 | AI 어시스턴트(매출 조회 + 사내 문서 검색) |
| 7 | 직접 분석도 한 화면에서 | SQL 편집기 |
| 8 | 17가지 차트로 시각화 | 차트 빌더 |
| 9 | 현황은 대시보드 한 장으로 | 대시보드(위·아래) |
| 10 | 반복되는 데이터 작업은 파이프라인으로 | 파이프라인 DAG(SQL + AI 분류) + 트리거 |
| 11 | 글로 된 데이터도 AI가 읽고 분류합니다 | AI 분류 결과(고객 문의 원문 → 긴급도·유형·근거) |
| 12 | 업무 개념과 관계를 지식 모델로 | 지식 모델 + 매핑 탭 |
| 13 | 흩어진 정보가 하나의 관계망으로 | 그래프 탐색(이웃 강조 + 관계 패널) |
| 14 | AI가 애매한 건 사람에게 묻습니다 | AI 검수 + 원문 근거 |
| 15 | 정기 보고서는 AI가 씁니다 | 스마트 작업 + AI 리포트 |
| 16 | 결과는 쓰는 곳으로 보냅니다 | 알림 채널 설정 |
| 17 | 누가 무엇을 하는지, 권한과 기록으로 | 역할 권한 + 감사 로그 |
| 18 | 우리 조직의 인프라 안에서 운영합니다 | 구성 개념도 |

## 작성 규칙

**화면에 없는 기능, 코드에 없는 동작을 쓰지 않습니다.** 자료가 고객에게 나가기 때문에 과장이 곧 신뢰 문제가 됩니다.

| 알아야 할 것 | 근거 |
| --- | --- |
| 화면이 있는지 | `apps/firehub-web/src/App.tsx` 라우터 |
| 버튼·메뉴·라벨 문구 | 해당 컴포넌트 소스의 한국어 문구(화면 캡처와 일치해야 함) |
| AI가 무엇을 하는지 | `apps/firehub-ai-agent/src/agent/subagents/*/agent.md`, 실제 실행 결과 |
| AI 권한 범위 | `JwtAuthenticationFilter#authenticateWithInternalToken` — AI 호출은 요청한 사용자의 권한으로 처리된다 |
| AI 모델 연결 방식 | `apps/firehub-ai-agent/src/providers/provider-factory.ts`, `OpencodePutValidator` |

- **AI 결과물은 지어내지 않습니다.** AI 답변·분류 결과·리포트는 `seed-ai.mjs`와 촬영 스크립트가 사람과 같은 방식(실행 버튼·채팅)으로 요청해 AI가 실제로 만든 것입니다.
- **AI 모델·벤더 이름을 쓰지 않습니다.** 화면·문구 모두 "AI", "AI 에이전트" 같은 일반 용어로 씁니다. 촬영 스크립트는 찍기 직전에 화면 글자(입력값·iframe 안 포함)에서 벤더·모델 이름을 찾아, 있으면 그 장면을 실패로 처리합니다(`assertNoVendor`). 그래서 **설정 › AI 에이전트 화면은 찍지 않습니다**(유형 이름·토큰 형식에 벤더 이름이 보인다).
- 확인한 근거가 없는 효과(시간 단축률 등)는 쓰지 않습니다.
- 시연 데이터는 일부러 **밖에 내보내면 안 되는 회사 데이터**(매출·매입 전표, 거래처·구매 담당자 정보, 고객 문의)로 잡습니다. Public AI·SaaS 플랫폼에 올릴 수 없는 데이터를 조직 안에서 다룬다는 제품 메시지를 화면으로 보여 주기 위해서입니다.
- 거래처 연락처는 뒷자리를 가린 형태(`010-1234-****`)로만, 메일은 `example.com` 으로만 넣습니다. 화면에 걸리는 번호가 실제 번호일 수 없게 하기 위해서입니다.
- 운영 데이터를 찍지 않습니다. 가상 회사(㈜누리테크)·가상 거래처·공급사와 `example.com` 계정만 씁니다. 행 데이터는 고정 시드 난수로 만들어 다시 돌려도 같은 숫자가 나옵니다. 지도 바탕은 실제 지도라 표지에 "가상 데이터" 각주를 둡니다.

## 다시 만들기

### 1. 격리 스택 띄우기

워크트리에서 작업합니다. 워크트리에는 `node_modules` 가 없으므로 메인 체크아웃 것을 심볼릭 링크로 겁니다(커밋하지 않는다 — `git add` 는 `docs/intro/` 만). 아래 상대 경로는 워크트리가 `.claude/worktrees/<이름>` 에 있을 때 기준입니다.

```bash
ln -sfn ../../../node_modules node_modules
ln -sfn ../../../../../apps/firehub-web/node_modules apps/firehub-web/node_modules
ln -sfn ../../../../../apps/firehub-ai-agent/node_modules apps/firehub-ai-agent/node_modules
# jOOQ 생성 소스는 디렉터리가 없을 때만 건다 — 이미 있으면 ln 이 그 안에 링크를 하나 더 만든다
[ -e apps/firehub-api/src/main/generated ] || ln -s ../../../../../../../apps/firehub-api/src/main/generated apps/firehub-api/src/main/generated
(cd apps/firehub-api && ./gradlew bootJar -x test -x generateJooq -q)
```

| 구성요소 | 포트 | 띄우는 방법 |
| --- | --- | --- |
| DB | 5452 | `reset-stack.sh` 가 컨테이너 `intro-data-db`(ghcr 운영 이미지)를 새로 만든다 |
| 그래프 DB | 7697 | `reset-stack.sh` 가 컨테이너 `intro-data-neo4j` 를 새로 만든다 |
| API | 5010 | `reset-stack.sh` → `start-api.sh`(local 프로필 + 격리 DB, JVM 시간대 UTC, 브랜드명 Gen:iA Data) |
| AI 에이전트 | 5020 | `apps/firehub-ai-agent` 에서 `PORT=5020 INTERNAL_SERVICE_TOKEN=firehub-local-dev-internal-token API_BASE_URL=http://localhost:5010/api/v1 NEO4J_URI=bolt://localhost:7697 NEO4J_PASSWORD=firehub-graph-dev BRAND_NAME="Gen:iA Data" MAX_TURNS=30 ./node_modules/.bin/tsx src/index.ts` |
| 웹 | 5273 | `apps/firehub-web` 에서 `./node_modules/.bin/vite --port 5273 --strictPort` |

웹 vite 프록시가 API 를 5010 으로 고정해 두었기 때문에 API·에이전트는 개발 기본 포트를 씁니다. **촬영 중에는 `pnpm dev` 를 함께 띄우지 않습니다.**

### 2. 한 번에 실행

```bash
INTRO_AI_TOKEN=<AI 에이전트 토큰> docs/intro/scripts/run-all.sh
```

토큰은 `claude setup-token` 으로 발급하고, 환경변수로만 넘깁니다(파일·커밋에 남기지 않는다). 시드가 테넌트 설정(AI 에이전트·AI 분류)에 등록합니다.

`run-all.sh` 는 다음 순서로 실행합니다(약 15분).

1. `reset-stack.sh`: DB·그래프 DB 를 비우고 API 를 다시 띄웁니다.
2. `seed-demo.mjs`: 가상 회사 데이터를 넣습니다. 구성원 4명, 데이터셋 7개(매출 전표 약 3,000건·매입 전표 약 1,400건·거래처 정보·고객 문의 원문·일별 매출 집계·AI 분류 결과·사내 문서 7건 `seed-docs/`), 저장 쿼리 9개, 차트 7개, 대시보드, 파이프라인(SQL + AI 분류, 트리거 2개), 리포트 양식과 스마트 작업, 임베딩 설정·행 검색·지식 모델(영업·고객)과 매핑이 포함됩니다.
3. 워크스페이스 이름을 "㈜누리테크"로 바꿉니다. 이름 변경 API 가 없어 DB 를 직접 고칩니다.
4. `seed-ai.mjs`: 파이프라인을 실행해 AI 가 고객 문의 원문을 분류하게 하고, AI 채팅으로 표를 지식그래프에 투영·사내 문서를 적재하게 하고(이때 AI 검수 대기 항목이 생긴다), 스마트 작업을 실행해 AI 가 주간 보고서를 쓰게 합니다.
5. `capture-screens.mjs`: 화면을 촬영합니다. AI 어시스턴트 장면은 이때 화면에서 직접 묻습니다. 일부만 다시 찍을 때는 `INTRO_SHOTS_ONLY=ai-chat,dashboard` 를 씁니다.
6. `build-pdf.mjs`: PDF 와 장별 PNG 를 만듭니다. 검토용 PNG 만 만들 때는 `--png-only` 를 씁니다.

### 3. 확인

- AI 결과물은 실행할 때마다 문구가 달라집니다. 촬영 후 `dist/intro/png/` 을 훑어 어색한 답변이 없는지 봅니다. 어색하면 해당 장면만 다시 돌립니다(`INTRO_SHOTS_ONLY=ai-chat`, 리포트만은 `INTRO_ONLY_JOB=1 node docs/intro/scripts/seed-ai.mjs`).
- 번호 표식은 대부분 촬영 때 기록한 요소 위치(`shots/boxes.js`)를 따라갑니다. 차트 카드처럼 테스트 ID 가 없는 영역은 `data-xywh`(원본 px)로 두었습니다 — 대시보드 격자·화면 배치가 바뀌면 이 값도 다시 맞춥니다.

### 점검 도구

| 스크립트 | 쓰임 |
| --- | --- |
| `api.mjs` | 촬영 계정으로 API 한 번 호출(`node docs/intro/scripts/api.mjs GET /datasets`) |
| `probe.mjs` | 주요 화면을 한 장씩 찍어 보기(소개 자료 원본이 아님) |
| `chat-probe.mjs` | AI 어시스턴트 질문을 SSE 로 보내 도구 호출·답변·벤더 노출 여부 확인 |

### 번호 표식

| 표기 | 쓰임 |
| --- | --- |
| `<b class="ann area" data-box="장면:키" data-n="1">` | 영역 — 빨간 테두리 투명 박스 |
| `<b class="ann point" data-box="장면:키" data-n="1" data-side="left" data-len="40"><i></i></b>` | 지점 — 요소 가장자리에 점, 선 끝에 번호 |
| `data-xywh="x,y,w,h"` | `data-box` 대신 원본 px 를 직접 준다 |
| `<div data-crop="x,y,w,h" data-max-h="690">` | 원본의 그 영역만 잘라 보여 주는 확대 패널 |

## 빌드 환경

- 슬라이드 글꼴은 Pretendard 입니다. `deck.css` 가 로컬에 설치된 글꼴(`local()`)을 씁니다.
- 흐림(blur) 그림자는 쓰지 않습니다. Chrome 이 PDF 에 래스터로 구워 macOS 미리보기에서 회색 사각형으로 보입니다.
- Playwright 는 `apps/firehub-web` 의 의존성을 쓰고, 브라우저는 시스템 크롬(`channel: 'chrome'`)입니다. 이 머신에서 `playwright install` 은 멈춥니다.
- 지도 바탕(openfreemap 타일)은 인터넷이 필요합니다. 자료에 "오프라인 지도"를 암시하는 문구를 쓰지 않습니다.

## 촬영 규격

- 데스크톱: 뷰포트 1440×810(16:9), 배율 2, 라이트 테마, `ko-KR`, `Asia/Seoul`.
- 브랜드: 웹은 `/config.js` 로 브랜드명을 읽습니다. 촬영 스크립트가 이 파일만 바꿔 끼워 "Gen:iA Data" 로 찍습니다(배포 화이트라벨과 같은 방식).

## 알려진 제약

- **임베딩은 외부 Ollama 를 씁니다.** 문서 검색·행 검색·지식그래프 동의어 판정에 임베딩이 필요해, 운영과 같은 Ollama(`http://bluelion.iptime.org:11434`, 모델 `bge-m3`, 1024차원)를 씁니다. `start-api.sh` 가 허용 목록에 넣고 시드가 설정에 저장합니다. 다른 서버를 쓰려면 `INTRO_OLLAMA_URL` 을 줍니다. 그 서버로는 가상 데이터만 나갑니다.
- **그래프 적재는 AI 채팅으로만.** 표 투영(`graphrag_project_table`)·문서 적재(`graphrag_ingest`)는 API 엔드포인트가 없고 AI 에이전트 도구로만 돕니다. 문서 적재는 비용이 커서 에이전트가 한 번 확인을 묻기 때문에 `seed-ai.mjs` 가 같은 세션에서 "네"로 답합니다.
- **AI 검수 항목은 AI 판단에 따라 달라집니다.** 문서에 쓴 줄임말(`seed-docs/07-현장영업메모.md` 의 "다솜", "버금" 등)을 AI 가 같은 거래처로 판단하면 동의어 검수 항목이 생깁니다. 직접 항목을 넣는 API 가 있지만 AI 결과를 지어내지 않는다는 원칙에 따라 쓰지 않습니다.
- **데이터셋 유형 대화상자의 "CSV, DB, API 등".** 화면 문구지만 외부 DB 직접 연결 기능은 없습니다(파일 업로드·API 가져오기·파이프라인). 그래서 슬라이드 문구에는 DB 연결을 쓰지 않았습니다.
- **마이그레이션 기본 지식 모델(화재조사 보고서)** 은 시연 주제와 무관해 시드가 지웁니다.

- **사내(사설망) AI 모델 서버를 연결할 수 없음.** 대화 모델의 OpenAI 호환 연결(opencode)은 baseURL 이 HTTPS·443/8443 포트여야 하고, SSRF 가드가 사설 IP(10.x·172.16~31.x·192.168.x)를 막습니다(`OpencodeProbeService`, `SsrfProtectionService`). 임베딩(Ollama)은 허용 목록(`EMBEDDING_OLLAMA_ALLOWED_BASE_URLS`)이 있어 사내 주소를 쓸 수 있습니다. 그래서 12장은 "관리자가 정한 곳에 표준 방식으로 연결"까지만 쓰고 "사내 모델 서버"라고 쓰지 않았습니다.
- **스마트 작업 리포트가 자주 비어 끝남.** 리포트 작성 하위 에이전트(report-writer, Write 도구만 가짐)가 작업 디렉터리 밖 임시 경로에 파일을 쓰지 못해, 리포트 파일 없이 답변 원문으로 대체되거나 실패합니다(7회 중 1회 성공 실측). sdk 유형은 스마트 작업에 모델을 싣지 않아 기본 모델로 도는 것도 겹칩니다. 촬영용 스마트 작업 지시문에 "하위 에이전트에 맡기지 말고 직접 Bash 로 세 파일을 저장"을 넣어 우회했습니다 — 그래서 10장은 스마트 작업의 분석 지시문 영역을 잘라 냅니다.
- **AI 답변 차트 범례가 영문 컬럼명.** AI 가 만든 쿼리의 별칭(예: `golden_rate_pct`)이 범례에 그대로 보입니다.
- **감사 로그 액션 표기 혼재.** `MEMBER_ADD` 같은 코드가 한국어 라벨 없이 보입니다.
