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
    queries: { retry: 1, staleTime: 30_000, refetchOnWindowFocus: false },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
