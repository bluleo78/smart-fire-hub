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

// 스크롤 중 스크롤바 강조. React 트리와 무관한 document 레벨 부수효과이므로 부트 시 1회만 설치한다
// (AppLayout 안에 두면 로그인/회원가입 등 레이아웃 밖 화면이 빠지고, StrictMode 이중 마운트도 겪는다).
installScrollbarAutoHide();

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
