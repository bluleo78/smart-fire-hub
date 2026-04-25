# Performance 관점 — 체감 성능과 자원 검증 (데스크탑 환경)

매트릭스 파일: `.coverage-matrix-perf.md`.

> 출처: web.dev Web Vitals · Chrome DevTools docs · React 19 release notes · TanStack Query v5 · React Flow Performance · Vite Build Guide. 권위 출처는 §4.

이 perspective는 **2024-03 이후 안정화된 INP**를 포함한 Core Web Vitals 기준으로 검증한다. 이 프로젝트는 **데스크탑 전용**(firehub-web)이라 모바일 시뮬레이션은 제외하고, 좁은 노트북(1280px) 환경의 CPU throttling만 적용한다.

## 1. Core Web Vitals 2024+ 임계값

모든 임계값은 **75퍼센타일** 기준. lab(Lighthouse/DevTools)과 field(CrUX/web-vitals.js) 결과는 다를 수 있어 둘 다 본다.

| 지표 | Good | Needs Improvement | Poor | 측정 (lab) | 측정 (field) | CWV? |
|---|---|---|---|---|---|---|
| **LCP** | ≤ 2.5s | 2.5~4.0s | > 4.0s | DevTools Performance / Lighthouse | web-vitals.js, CrUX | ✅ |
| **INP** | ≤ 200ms | 201~500ms | > 500ms | DevTools Performance "Interactions" | web-vitals.js (모든 click/key의 p98) | ✅ (2024-03, FID 대체) |
| **CLS** | ≤ 0.1 | 0.1~0.25 | > 0.25 | Lighthouse / DevTools | web-vitals.js (5s window) | ✅ |
| **TTFB** | ≤ 0.8s | 0.8~1.8s | > 1.8s | Network "Waiting (TTFB)" | Server-Timing, CrUX | ❌ (LCP 진단) |
| **FCP** | ≤ 1.8s | 1.8~3.0s | > 3.0s | Lighthouse | web-vitals.js | ❌ |
| **TBT** | < 200ms | 200~600ms | > 600ms | Lighthouse only | (lab 전용) | INP의 lab proxy |

**핵심 메모**:
- **LCP 후보**: `<img>`, SVG `<image>`, `<video>` poster, CSS background-image, 블록 텍스트. opacity:0/풀뷰포트 오버레이/플레이스홀더 제외.
- **CLS = impact fraction × distance fraction**, session window 1초 간격 max 5초. 사용자 입력 후 500ms 내 시프트는 제외.
- **INP**: click/tap/key만 카운트. scroll/hover 제외. 50회당 1회 outlier 무시.
- **TTFB는 CWV 아님** — LCP/FCP 디버깅 시작점.
- **FID는 2024-03 폐기**. 옛 보고서면 outdated.

## 2. 카테고리별 시나리오 (28개)

본 프로젝트의 3대 무거운 페이지(데이터셋 목록, 파이프라인 캔버스, AI 채팅 SSE)를 우선 적용.

### 2.1 초기 로딩 (LCP / FCP / TTFB)
- [ ] 첫 진입 LCP < 2.5s (데이터셋 목록·파이프라인 상세·대시보드)
- [ ] LCP 요소 식별 (DevTools Performance "Timings" 트랙) — placeholder가 LCP면 fail 신호
- [ ] 핵심 라우트(`/`, `/datasets`, `/pipelines`)에 메인 청크 + lazy 청크 분리
- [ ] 폰트 `font-display: swap` 또는 preload — FOUT/FOIT로 LCP 지연 X
- [ ] HTML에 `<link rel="preconnect">` / `prefetchDNS`가 외부 도메인(API, CDN)에 적용
- [ ] hero 이미지에 `fetchpriority="high"` 또는 React 19 `preload(..., {as:'image'})`

### 2.2 응답성 (INP / TBT)
- [ ] 데이터셋 목록 100건 렌더 직후 행 클릭 INP < 200ms
- [ ] xyflow 50+ 노드 캔버스에서 노드 드래그/줌/팬 60fps 유지 (Performance "Frames" 트랙)
- [ ] AI 채팅 SSE 스트리밍 중 다른 버튼 클릭 INP < 200ms (메인 스레드 점유 검증)
- [ ] CodeMirror 빠른 타이핑(20+ wpm) 시 키 입력당 long task 없음
- [ ] 검색 입력은 debounce(≥ 250ms) 또는 `useDeferredValue` 적용
- [ ] 정렬/필터 토글이 전체 재페치 아닌 클라이언트 정렬

### 2.3 안정성 (CLS)
- [ ] 이미지/아바타에 `width`/`height` 또는 `aspect-ratio` 명시
- [ ] 스켈레톤 사이즈가 실제 콘텐츠와 ±5% 이내 일치
- [ ] 토스트/배너가 기존 콘텐츠 아래로 밀어내지 않음 (overlay/transform)
- [ ] 폰트 fallback과 web font의 metric 유사 (`size-adjust`)

### 2.4 메모리 / 누수
- [ ] `/pipelines/N` ↔ `/datasets` 10회 왕복 후 heap < +5MB 증가
- [ ] SSE 채팅 페이지 이탈 시 EventSource/ReadableStream `cancel()` 호출 (Network EventStream 종료 확인)
- [ ] xyflow 캔버스 unmount 시 ResizeObserver/노드 ref가 detached로 남지 않음
- [ ] CodeMirror unmount 시 `EditorView.destroy()` 호출 (heap snapshot에서 EditorView 0개)
- [ ] `addEventListener` (resize/keydown/message)에 대응하는 `removeEventListener` 존재

### 2.5 네트워크 효율
- [ ] 동일 queryKey 중복 fetch 없음 (TanStack Query devtools에서 inflight 검사)
- [ ] window 포커스 전환 시 의도하지 않은 refetch 폭주 없음
- [ ] 페이지네이션/검색에서 placeholderData/keepPreviousData로 깜빡임 방지
- [ ] gzip/br 압축 응답, 정적 자산에 1y immutable cache
- [ ] API 응답에 `ETag` 또는 `Cache-Control: max-age` — 304 활용
- [ ] SSE 스트림 종료 후 reconnect 폭주 없음 (지수 backoff)

### 2.6 번들 크기
- [ ] 메인 청크 < 200KB gzip (route별 split 후)
- [ ] 무거운 라이브러리 dynamic import: `@xyflow/react`(파이프라인 라우트만), CodeMirror(에디터만), react-markdown+plugins(채팅만)
- [ ] `lucide-react`는 named import만 (default/wildcard 금지)
- [ ] `dayjs` 사용 또는 `date-fns` 함수별 import. `moment` 금지
- [ ] `lodash` 대신 `lodash-es` 함수별 import 또는 native ES

## 3. React 19 + Vite + TanStack Query 환경 특화 함정

### 3.1 React 19
- **`use(promise)` 렌더 중 생성 금지** — 매 렌더 새 promise면 캐시 안 되고 무한 suspend. 부모에서 promise 만들어 prop으로
- **`useActionState` / `useTransition`로 isPending 자동화** — 수동 `useState(false)` + try/finally는 batching 깨뜨림
- **`useDeferredValue(value, initialValue)`** — React 19부터 initial value 지원. 무거운 리스트(데이터셋 100건)에 `useDeferredValue(query)` + `<Suspense>` 조합
- **ref cleanup 함수** — `ref={(el) => { ...; return () => {...} }}` 형태. unmount 시 별도 `null` 호출 사라져 detached 노드 감소
- **stylesheet `precedence` 빠짐** — 동적 삽입 CSS dedup 안 됨. CodeMirror/xyflow 같은 동적 스타일 라이브러리 주의
- **React Compiler 도입 시** — 기존 수동 `useMemo`/`useCallback` 안 지우면 컴파일러가 두 번 일하고 의존성 실수 묻힘

### 3.2 Vite
- **`build.rollupOptions.output.manualChunks`** — vendor 명시 분리. `@xyflow/react`, `@codemirror/*`, `react-markdown` + `remark-*`/`rehype-*`는 별도 청크
- **`build.target`** — 모던 브라우저(Chrome 111+, Safari 16.4+, Firefox 114+)면 ES2022 타겟해서 polyfill 제거
- **`build.cssCodeSplit: true`** 유지 — route별 CSS 분리
- **`vite-bundle-visualizer` / `rollup-plugin-visualizer`** — duplicate 패키지(react 두 버전, lodash 두 버전) 식별 필수

### 3.3 TanStack Query v5 기본값 함정
| 옵션 | 기본값 | 함정 |
|---|---|---|
| `staleTime` | **0ms** | 즉시 stale → mount/focus/reconnect마다 refetch 폭주. 의미 있는 값(30s~5min) 필수 |
| `gcTime` | 5분 | 5분 안에 재방문하면 hit, 그 이후 GC. 길게 잡으면 메모리 누적 |
| `refetchOnWindowFocus` | true | 탭 전환만 해도 재요청. 자주 안 바뀌는 화면은 false |
| `refetchOnMount` | true | 같은 키 두 컴포넌트 동시 mount 시 두 번 fetch — `staleTime`으로 차단 |
| `retry` | 3회 + exponential backoff | 4xx도 재시도 — `retry: (n, err) => err.status >= 500`로 제한 |

**queryKey 함정**: `{id:1, _ts:Date.now()}`처럼 객체에 가변 필드 넣으면 캐시 절대 hit 안됨.

### 3.4 xyflow / @xyflow/react
- `nodeTypes` / `edgeTypes` 반드시 컴포넌트 외부 선언 또는 `useMemo`. 매 렌더 새 객체면 모든 노드 unmount/remount
- 커스텀 노드는 `React.memo` + props 의존 함수는 `useCallback`
- 노드 100개 이상일 때 `onlyRenderVisibleElements` 활성화
- 드래그 중 상태 갱신은 `nodesDraggable` 제어 + `onNodesChange`에서 throttle
- 선택자는 `useStore(selector, shallow)`로 불필요 구독 차단

### 3.5 CodeMirror 6
- `extensions` 배열은 컴포넌트 외부 선언 또는 `useMemo` — 매번 새 배열이면 EditorView 재생성
- 동적 옵션은 **Compartment** 패턴으로 `dispatch`. EditorView 자체는 유지
- React state ↔ CM state 동기화는 이벤트 emitter로 분리
- unmount 시 `view.destroy()` 호출 보장 (useEffect cleanup)

### 3.6 SSE / EventSource (AI 채팅)
- `useEffect`에 `AbortController` 또는 `EventSource.close()` cleanup 필수
- 스트림 도중 라우트 전환 시 race: `signal.aborted` 체크 후 setState
- 자동 재연결 폭주 차단: `EventSource` 기본 자동 reconnect — fetch + ReadableStream 구현이라면 수동 backoff
- Network 패널 "EventStream" 탭에서 수신 메시지 + 종료 검증

## 4. playwright-cli + DevTools 측정 워크플로우

자동 도구가 못 잡는 부분은 사람의 손이 필요. 이 섹션은 **수동 캡처 절차**.

### 4.1 공통 사전 준비
1. headed Chrome 시작 후 페이지 진입
2. F12 → Network "Disable cache" ON, **CPU throttling 4x slowdown** (좁은 노트북 시뮬레이션 — 모바일 시뮬레이션은 안 씀)
3. Application → Storage → Clear site data로 cold start 보장

### 4.2 초기 로딩 측정 (LCP/FCP/CLS)
1. Performance 패널 → 톱니 → "Enable advanced rendering instrumentation"
2. ⌘E (record) → 페이지 reload (⌘⇧R) → 메인 콘텐츠 보일 때까지 → stop
3. **사람 캡처**:
   - Timings 트랙 LCP 마커 + LCP 후보 요소 스크린샷 (placeholder가 LCP면 즉시 fail)
   - Layout Shifts 트랙에 표시된 시프트 박스
   - Main 트랙의 long task(>50ms) 위치
4. trace를 `test-results/exploratory/perf/<feature>/<timestamp>/trace.json`에 export

### 4.3 응답성 측정 (INP) — 페이지별 시나리오
**데이터셋 목록**: 100건 로드 후 행 클릭 → 상세 패널 오픈
**파이프라인 캔버스**: 50+ 노드 로드 후 노드 드래그 5초간
**AI 채팅 SSE**: 응답 스트리밍 중 좌측 메뉴 클릭

각 시나리오:
1. Performance record → 인터랙션 1회 → stop
2. Performance 상단 **Interactions** 카드에서 INP ms 확인
3. Interactions 트랙에서 input delay / processing / presentation 분해
4. 200ms 초과 시 Bottom-Up 탭 self time 정렬 상위 5개 함수 + 콜스택 캡처

### 4.4 메모리 누수 비교 (3-snap 패턴)
1. Memory → "Heap snapshot" → snapshot 1 (baseline)
2. 라우트 왕복 또는 모달 open/close 10회
3. `window.gc()` 강제 GC (Chrome `--js-flags="--expose-gc"` 필요) 또는 휴지통 아이콘
4. snapshot 2 → 다시 10회 → snapshot 3
5. snapshot 3에서 Comparison view → snapshot 1 기준 `# Delta` 정렬
6. **사람 캡처**:
   - `Detached <HTMLDivElement>` 등의 retainer 체인 (yellow=JS 참조, red=detached). retainer가 React fiber 경로면 컴포넌트 cleanup 누락
   - `EditorView`, `EventSource`, `ReadableStreamDefaultReader`, `ResizeObserver` 인스턴스 0개 도달 여부
7. +5MB 미만 통과, +20MB 이상 critical

### 4.5 번들 검증
1. `pnpm build` 후 `vite-bundle-visualizer` 또는 `rollup-plugin-visualizer` 실행 → treemap HTML
2. **사람 캡처**:
   - 메인(entry) 청크에 `@xyflow/react`, `@codemirror/*`, `react-markdown`이 들어가있으면 dynamic import 누락
   - duplicate 패키지(같은 라이브러리 두 버전)
   - `lucide-react`가 25KB 넘으면 default/wildcard import 의심
3. DevTools Coverage 탭(⌘⇧P → Coverage) → reload → 청크별 unused %. 70%+ unused면 split 후보

### 4.6 네트워크 효율
1. Network 패널 → reload → Headers/Size/Time/Waterfall 컬럼
2. **사람 캡처**:
   - 같은 URL 중복 요청 (TanStack Query refetch) — Initiator로 트리거 추적
   - Content-Encoding 빠진 큰 응답(>50KB)
   - Cache-Control이 `no-cache` 또는 비어있는 정적 자산
   - EventStream 탭에서 SSE가 페이지 이탈 시 종료(Status: Cancelled)되는지

### 4.7 playwright-cli 한계와 보완
- playwright-cli만으로는 LCP/INP 정확 측정 어려움. 콘솔 캡처는 가능:
  ```bash
  playwright-cli -s=$SESSION console "warning|slow|deprecated|memory|leak"
  ```
- `web-vitals` CDN 스니펫 inject로 콘솔 로그로 LCP/INP/CLS 출력 후 playwright-cli 콘솔 수집은 자동화 가능
- 그러나 **flame chart / heap snapshot / retainer 체인 / Interactions 트랙**은 사람 눈 필수. §4.2~4.6은 모두 사람이 DevTools에서 수행하고 스크린샷을 `test-results/exploratory/perf/<page>/<timestamp>/screenshots/`에 저장

## 5. 권위 출처

- **Web Vitals 개요**: https://web.dev/articles/vitals
- **INP**: https://web.dev/articles/inp · **LCP**: https://web.dev/articles/lcp · **CLS**: https://web.dev/articles/cls
- **TTFB**: https://web.dev/articles/ttfb · **FCP**: https://web.dev/articles/fcp
- **Diagnose slow interactions**: https://web.dev/articles/manually-diagnose-slow-interactions-in-the-lab
- **Chrome DevTools Performance**: https://developer.chrome.com/docs/devtools/performance/reference
- **Heap snapshots**: https://developer.chrome.com/docs/devtools/memory-problems/heap-snapshots
- **Memory problems guide**: https://developer.chrome.com/docs/devtools/memory-problems
- **React 19 Release Notes**: https://react.dev/blog/2024/12/05/react-19
- **TanStack Query Important Defaults**: https://tanstack.com/query/latest/docs/framework/react/guides/important-defaults
- **TanStack Query Caching**: https://tanstack.com/query/latest/docs/framework/react/guides/caching
- **React Flow Performance**: https://reactflow.dev/learn/advanced-use/performance
- **CodeMirror + React**: https://thetrevorharmon.com/blog/codemirror-and-react/
- **Vite Build Guide**: https://vite.dev/guide/build.html

## 6. 의도적으로 제외 (Lighthouse / 자동 도구가 처리)

탐색 단계에서 **사람이 시간 쓸 가치가 없는** 항목. Lighthouse / PageSpeed Insights / `pnpm build`가 자동으로 점수·경고. 이슈로 등록하지 말고 도구 리포트 링크만 첨부.

- 이미지 최적화 (next-gen format webp/avif, properly sized images) — Lighthouse "Opportunities"
- render-blocking resources 자동 감지 — Lighthouse
- 사용 안 되는 CSS/JS 비율 — Coverage 탭. 70% 이상일 때만 사람이 split 결정
- 압축 미적용 응답 — Lighthouse "Enable text compression"
- HTTPS / 보안 헤더 — Lighthouse Best Practices + 보안 perspective
- alt 누락, color contrast — `a11y` perspective에서. perf에서 중복 검사 X
- 번들 크기 raw 측정 — `vite build` 출력. 사람은 "어떤 청크에" 전략만 판단
- Preload key requests / preconnect 빠짐 — Lighthouse 자동 제안
- HTTP/2/3 사용 여부 — Network Protocol 컬럼
- font-display swap — Lighthouse
- third-party 도메인 영향 — Lighthouse

**모바일 시뮬레이션은 본 프로젝트 미적용** (firehub-web 데스크탑 전용).

**사람이 봐야 하는 것 vs 도구**:

| 항목 | 도구 | 사람 |
|---|---|---|
| LCP/FCP/CLS 점수 | Lighthouse ✅ | LCP 후보가 옳은지, 시프트 원인 식별 |
| INP 점수 | DevTools ✅ | 어느 함수가 long task인지 콜스택 분석 |
| 번들 크기 | visualizer ✅ | 어떤 청크로 나눌지 전략 |
| unused JS | Coverage ✅ | split 후 lazy 시점 결정 |
| 메모리 누수 | ❌ | heap snapshot 비교 + retainer 체인 |
| race condition | ❌ | 라우트 빠른 전환 시 SSE/Query 동작 |
| xyflow 드래그 fps | ❌ | Performance Frames 트랙 |
| TanStack Query 중복 fetch | ❌ | devtools에서 동일 key inflight |
| CodeMirror EditorView 재생성 | ❌ | heap snapshot 인스턴스 수 |

## 7. 이슈 등록 라벨

- `bug,severity:major,perf` — INP 200~500ms, LCP 2.5~4s, 메모리 +5~20MB
- `bug,severity:critical,perf` — INP > 500ms, LCP > 4s, 메모리 +20MB+, 60fps 깨짐
- `bug,severity:minor,perf` — CLS 0.1~0.25, 청크 split 누락 등 체감 영향 작음

`perf` 라벨이 붙은 이슈는 측정값 + DevTools 캡처가 필요해 pilot 자율 처리 제외, 사람 검토 라우팅.
