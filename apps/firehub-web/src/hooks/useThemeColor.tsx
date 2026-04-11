import { createContext, useCallback, useContext, useEffect, useState } from 'react';

type ThemeColor = 'indigo' | 'ocean' | 'sunset';

interface ThemeColorContextValue {
  themeColor: ThemeColor;
  setThemeColor: (color: ThemeColor) => void;
}

const ThemeColorContext = createContext<ThemeColorContextValue>({
  themeColor: 'indigo',
  setThemeColor: () => {},
});

export function ThemeColorProvider({ children }: { children: React.ReactNode }) {
  const [themeColor, setThemeColorState] = useState<ThemeColor>(() => {
    return (localStorage.getItem('theme-color') as ThemeColor) || 'indigo';
  });

  const setThemeColor = useCallback((color: ThemeColor) => {
    setThemeColorState(color);
    localStorage.setItem('theme-color', color);
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    root.classList.remove('theme-indigo', 'theme-ocean', 'theme-sunset');
    root.classList.add(`theme-${themeColor}`);
  }, [themeColor]);

  return (
    <ThemeColorContext.Provider value={{ themeColor, setThemeColor }}>
      {children}
    </ThemeColorContext.Provider>
  );
}

// Fast refresh는 컴포넌트만 export하는 파일에서만 동작한다. 훅 export는 별도 파일로 분리하거나
// 이 경계 규칙을 비활성화해야 한다. 현재 파일은 Provider + 훅을 함께 유지하기 위해 비활성화.
// eslint-disable-next-line react-refresh/only-export-components
export function useThemeColor() {
  return useContext(ThemeColorContext);
}
