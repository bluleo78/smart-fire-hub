# playwright-cli 알려진 함정

## 1. `<main>` 오버레이가 클릭을 가로채는 경우
증상: `playwright-cli click` 후 반응 없음. 투명 패널이 위에 덮여 있음.

```bash
playwright-cli eval "(()=>{ const el=document.querySelector('[aria-label*=\"AI\"]'); el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true})); })()"
```

## 2. 클릭 후 hover 상태 고착
증상: 버튼 클릭 후 툴팁/오버레이가 사라지지 않아 다음 동작이 막힘.

```bash
playwright-cli mousemove 600 400  # 빈 공간으로 마우스 이동
```

## 3. ref 기반 선택자 작동 안 함
`[ref=e123]` 형태는 playwright-cli에서 미지원. 대신:
```bash
playwright-cli click "button:has-text('텍스트')"
playwright-cli click "[role='button'][aria-label*='키워드']"
playwright-cli click "input[placeholder*='플레이스홀더']"
```

## 4. 스트리밍/비동기 완료 대기
AI 응답이나 데이터 로딩처럼 완료까지 시간이 걸리는 경우:
```bash
playwright-cli run-code "
  await new Promise((resolve, reject) => {
    const check = async () => {
      const text = await page.evaluate(() => document.body.innerText);
      if (!text.includes('로딩중') && !text.includes('생각하는 중')) resolve();
      else setTimeout(check, 500);
    };
    check();
    setTimeout(() => reject(new Error('timeout')), 30000);
  });
"
```

## 5. 명령 타임아웃 (30초 기본)
`waitForFunction`이 30초를 넘으면 playwright-cli 자체가 타임아웃.  
해결: 긴 작업은 2단계로 분리 — 먼저 트리거, 그다음 대기.

## 6. Radix UI 탭/컴보박스 — 텍스트 선택자로 클릭 안 됨
증상: `click "tab:has-text('필드')"` 또는 `[role='tab']` 클릭 후 반응 없음.  
해결: `getBoundingClientRect()`로 좌표를 구한 뒤 mousemove+mousedown+mouseup 시퀀스 사용.

```bash
# 1) 탭 좌표 파악
playwright-cli eval "JSON.stringify([...document.querySelectorAll('[role=tab]')].map(t=>({text:t.textContent,rect:t.getBoundingClientRect()})))"

# 2) 해당 좌표로 클릭
playwright-cli mousemove 350 120
playwright-cli mousedown
playwright-cli mouseup
```

## 7. `eval` 다중 문장 실패
증상: `playwright-cli eval "const x = 1; return x;"` → SyntaxError.  
`eval`은 단일 표현식(expression)만 허용.

```bash
# 잘못된 예
playwright-cli eval "const el = document.querySelector('button'); el.click();"

# 올바른 예 — IIFE
playwright-cli eval "(()=>{ const el=document.querySelector('button'); el.click(); })()"
```

## 8. 파일 업로드 경로 제한
증상: `playwright-cli upload /tmp/test.csv` → "Path not allowed"  
playwright-cli는 프로젝트 디렉토리 내부 파일만 허용.

```bash
cp /tmp/test.csv .playwright-cli/test.csv
playwright-cli eval "document.querySelector('input[type=file]').dispatchEvent(new MouseEvent('click',{bubbles:true}))"
playwright-cli upload .playwright-cli/test.csv
```

## 9. 더블클릭 — `dblclick` 이벤트 직접 발송
`playwright-cli`에는 더블클릭 전용 명령 없음.

```bash
playwright-cli eval "document.querySelector('tr.data-row').dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true}))"

# 특정 인덱스의 행
playwright-cli eval "document.querySelectorAll('[role=row]')[2].dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true}))"
```

## 10. React 입력값 — `input.value=X`로는 React 상태 갱신 안 됨
증상: DOM value 직접 변경 후 폼 제출해도 빈 값으로 전송됨.  
React는 `nativeInputValueSetter`를 통해서만 상태 동기화.

```bash
# 좌표 클릭으로 포커스 후 type 사용
playwright-cli eval "JSON.stringify(document.querySelector('input[name=name]').getBoundingClientRect())"
playwright-cli mousemove 400 300
playwright-cli mousedown
playwright-cli mouseup
playwright-cli press "Control+a"
playwright-cli type "새 입력값"
```

## 11. Strict mode — 동일 선택자에 여러 요소 매칭
증상: `click "button:has-text('삭제')"` → "strict mode violation: multiple elements match"

```bash
# eval로 인덱스 지정
playwright-cli eval "(()=>{ const btns=[...document.querySelectorAll('button')].filter(b=>b.textContent.trim()==='삭제'); btns[0].click(); })()"

# 또는 부모 범위 한정
playwright-cli click "[role='dialog'] button:has-text('확인')"
```

## 12. `run-code` 문법 제한 — 변수 선언·세미콜론 분리 불가
증상: `run-code "const x = 1; return x;"` → `SyntaxError: Unexpected token 'const'`  
`run-code`는 단일 표현식만 허용. 여러 동작은 별개 호출로 분리.

```bash
# 잘못된 예
playwright-cli run-code "const reqs = []; page.on('request', r => reqs.push(r)); return reqs;"

# 올바른 예 — 단일 표현식
playwright-cli run-code "page.on('request', r => { if(r.url().includes('/api/')) console.log(r.url()) })"
```

## 13. hover-only 버튼 — opacity 0 상태로 DOM에 존재
증상: `querySelector('button[title=삽입]')` → 찾지만 click() 무반응  
원인: `opacity: 0; group-hover:opacity-100` 패턴.

```bash
# 컨테이너 행 hover 후 visible 버튼 찾기
playwright-cli mousemove <row-x> <row-y>
playwright-cli eval "(()=>{ const btns=[...document.querySelectorAll('button[title=\"삽입\"]')]; const v=btns.filter(b=>window.getComputedStyle(b).opacity!=='0'); return JSON.stringify({visible:v.length,rect:v[0]?.getBoundingClientRect()}); })()"
playwright-cli mousemove <btn-x> <btn-y>
playwright-cli mousedown
playwright-cli mouseup
```

## 14. playwright-cli 세션 충돌
증상: `open` 실행 시 "Session already exists" 오류.

```bash
playwright-cli -s=$SESSION close 2>/dev/null || true
playwright-cli -s=$SESSION --headed open <URL>
```

> **금지**: `kill-all`은 사용자가 열어 둔 다른 브라우저까지 강제 종료하므로 절대 사용하지 않는다.

## 15. `run-code`에서 `page.goto()` 실패
증상: `run-code "await page.goto('/pipelines')"` → `SyntaxError: Unexpected identifier 'page'`  
해결: 페이지 이동은 `eval`로 `window.location.href`를 직접 변경한다.

```bash
# 잘못된 예
playwright-cli run-code "await page.goto('http://localhost:5173/pipelines')"

# 올바른 예 — eval로 SPA 라우팅
playwright-cli eval "window.location.href='/pipelines'"
sleep 1.5  # 라우팅 완료 대기 필수
```

## 16. `beforeunload` 다이얼로그가 자동 탐색을 막는 경우
증상: PipelineEditorPage 등 unsaved-change 가드가 있는 페이지에서 `eval "window.location.href=..."` 실행 시
브라우저 이탈 확인 다이얼로그("사이트를 새로고침하시겠습니까?")가 떠서 다음 명령이 모두 블록됨.

해결: 세션 오픈 직후 또는 해당 페이지 진입 직전에 `beforeunload` 리스너를 제거한다.

```bash
# 브라우저 오픈 + 로그인 후 즉시 실행 — beforeunload 가드 전역 해제
playwright-cli -s=$SESSION run-code "
  window.addEventListener('beforeunload', (e) => { e.stopImmediatePropagation(); }, true);
"

# 또는 이미 PipelineEditorPage에 진입한 경우 이탈 직전에 실행
playwright-cli -s=$SESSION eval "window.onbeforeunload = null"
playwright-cli -s=$SESSION run-code "
  window.removeEventListener('beforeunload', window._beforeUnloadHandler);
"
# 그래도 안 되면 eval로 직접 제거
playwright-cli -s=$SESSION eval "(()=>{ const old=window.onbeforeunload; window.onbeforeunload=null; })()"
```

**중요**: 이 함정은 unsaved-change 가드가 있는 모든 에디터 페이지에서 발생한다.
PipelineEditorPage, 기타 폼 에디터 등 진입 후 이탈 시 반드시 위 해제 코드를 먼저 실행하라.

## 17. 로그인 폼 — `fill`이 React 상태에 반영되지 않는 경우
증상: `fill "input[placeholder*='email']" "..."` 실행 후 로그인 클릭 시 "유효한 이메일 형식의 아이디를 입력하세요" 오류 노출. 이메일 필드가 비어 있는 것처럼 동작.

원인: React controlled input은 focus 없이 fill만 실행하면 nativeInputValueSetter가 제대로 트리거되지 않는 경우가 있음.

**안전한 로그인 절차** (반드시 아래 순서로 실행):
```bash
# 1) 로그인 페이지 이동
playwright-cli -s=$SESSION eval "window.location.href='/login'"
sleep 1

# 2) 이메일 필드 클릭(포커스) 후 fill
playwright-cli -s=$SESSION click "input[placeholder*='email']"
sleep 0.3
playwright-cli -s=$SESSION fill "input[placeholder*='email']" "bluleo78@gmail.com"
sleep 0.3

# 3) 이메일 입력 확인 — placeholder가 여전히 보이면 type으로 재시도
playwright-cli -s=$SESSION --raw snapshot > /tmp/snap_login.yml 2>/dev/null
if grep -q "email@example.com" /tmp/snap_login.yml; then
  # fill이 실패했으면 type으로 재시도 (pitfall #10 방식)
  playwright-cli -s=$SESSION click "input[placeholder*='email']"
  playwright-cli -s=$SESSION press "Control+a"
  playwright-cli -s=$SESSION type "bluleo78@gmail.com"
  sleep 0.3
fi

# 4) 비밀번호 클릭 후 fill
playwright-cli -s=$SESSION click "input[type='password']"
sleep 0.3
playwright-cli -s=$SESSION fill "input[type='password']" "ehdgml88"
sleep 0.3

# 5) 로그인 버튼 클릭
playwright-cli -s=$SESSION click "button:has-text('로그인')"
sleep 2

# 6) 로그인 성공 확인
playwright-cli -s=$SESSION --raw snapshot > /tmp/snap_home.yml 2>/dev/null
if grep -q "email@example.com\|로그인" /tmp/snap_home.yml; then
  echo "LOGIN_FAILED — 로그인 페이지에 여전히 있음"
else
  echo "LOGIN_OK"
  playwright-cli -s=$SESSION state-save .playwright-cli/state.json
fi
```
