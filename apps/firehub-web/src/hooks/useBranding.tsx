import { createContext, useContext } from 'react';

import { getBranding } from '@/lib/branding';
import type { BrandingConfig } from '@/types/branding';

// 런타임 브랜딩은 앱 실행 중 바뀌지 않으므로 한 번만 읽어 컨텍스트에 고정한다.
const BrandingContext = createContext<BrandingConfig>(getBranding());

/**
 * 앱 전역에 런타임 브랜딩(브랜드명 · 로고 · 파비콘)을 제공한다.
 * getBranding()은 동기이므로 로딩 상태 없이 즉시 확정값을 내려준다.
 */
export function BrandingProvider({ children }: { children: React.ReactNode }) {
  return (
    <BrandingContext.Provider value={getBranding()}>
      {children}
    </BrandingContext.Provider>
  );
}

// Fast refresh는 컴포넌트만 export하는 파일에서만 동작한다. Provider + 훅을 함께 두기 위해 비활성화.
// eslint-disable-next-line react-refresh/only-export-components
export function useBranding() {
  return useContext(BrandingContext);
}
