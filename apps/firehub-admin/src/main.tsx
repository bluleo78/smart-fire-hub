import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/inter/800.css';
import './index.css';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import App from './App.tsx';
import { isClientError } from './lib/http-errors';
import { installScrollbarAutoHide } from './lib/scrollbar-autohide';

// 스크롤 중 스크롤바 강조. React 트리와 무관한 document 레벨 부수효과라 부트 시 1회만 설치한다.
installScrollbarAutoHide();

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // "4xx 는 재시도해도 결과가 같다"는 엔드포인트/상태코드의 성질이지 화면의 것이
      // 아니다 — 그래서 화면마다 `retry: false` 를 반복하는 대신 여기 한 곳에서 결정한다.
      // 4xx 가 아닌 실패(네트워크·5xx)는 기존과 같이 최대 1회 재시도한다.
      retry: (failureCount, error) => !isClientError(error) && failureCount < 1,
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
);
