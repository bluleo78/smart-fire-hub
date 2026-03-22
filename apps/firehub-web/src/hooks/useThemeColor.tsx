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

export function useThemeColor() {
  return useContext(ThemeColorContext);
}
