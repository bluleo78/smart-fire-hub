import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/inter/800.css';
import './index.css'

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import App from './App.tsx'
import { isEmptyResponseError, isForbiddenError, isTooManyRequestsError, isUnauthorizedError, isUserSwitchedError } from './lib/api-error'
import { installScrollbarAutoHide } from './lib/scrollbar-autohide'
import { installPopstateGuardInterceptor } from './lib/unsaved-changes-guard-registry'

// 스크롤 중 스크롤바 강조. React 트리와 무관한 document 레벨 부수효과이므로 부트 시 1회만 설치한다
// (AppLayout 안에 두면 로그인/회원가입 등 레이아웃 밖 화면이 빠지고, StrictMode 이중 마운트도 겪는다).
installScrollbarAutoHide();

// 이슈 #562: useUnsavedChangesGuard의 popstate(브라우저 뒤로/앞으로) 가로채기는 반드시
// BrowserRouter가 자신의 popstate 리스너를 등록하기 전에 먼저 설치돼야 한다(등록 순서가
// 이벤트 디스패치 순서를 결정하므로). React 트리 렌더보다 먼저, 앱 부팅 시 1회 설치한다.
installPopstateGuardInterceptor();

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 권한 부족(403)은 다시 시도해도 같다 — 재시도 없이 바로 권한 안내를 보이게 한다 (WD-122·WD-126).
      // 요청이 너무 많음(429, 동시 실행 상한)도 곧바로 다시 하면 같은 상한에 걸리고 부하만 더한다 — 재시도 없이 화면 안내로 넘긴다(WD-686).
      // 성공 응답인데 본문이 없음(WD-693)도 곧바로 다시 받아도 같기 쉬워 재시도 없이 화면 안내(「다시 시도」)로 넘긴다.
      // 인증 만료 401 도 다시 시도하지 않는다 (WD-1082) — 인터셉터는 401 을 받으면 refresh 로 한 번 갱신해
      // 원래 요청을 스스로 다시 보내므로, 조회에 401 이 도달한 것은 refresh 까지 실패했거나(세션 만료)
      // 갱신 뒤 재발송도 401 이거나 테넌트 강등이라 첫 401 을 그대로 reject 한 경우다. 셋 다 재시도해도
      // 결과가 같다. 재시도는 1초 뒤 다시 401 → refresh 401 을 만들어 만료 이동을 한 번 더 일으키고,
      // 떠나기 확인을 취소한 사용자에게 확인 창을 다시 띄웠다.
      // 다른 사용자 전환(UserSwitchedError, 그것을 감싼 TokenRefreshError)도 재시도하지 않는다 (WD-1122) —
      // 이미 홈으로 하드 이동 중이고, 재시도는 같은 거절만 되풀이하며 이동을 한 번 더 건다.
      // 그 밖은 1회 재시도.
      retry: (failureCount, error) =>
        !isForbiddenError(error) &&
        !isTooManyRequestsError(error) &&
        !isEmptyResponseError(error) &&
        !isUnauthorizedError(error) &&
        !isUserSwitchedError(error) &&
        failureCount < 1,
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
