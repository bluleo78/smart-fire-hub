import { type RefObject, useEffect, useLayoutEffect, useState } from 'react';

export function useWidgetVisibility(containerRef: RefObject<HTMLDivElement | null>) {
  const [isVisible, setIsVisible] = useState(true);
  const [layoutSettled, setLayoutSettled] = useState(false);

  // Wait for react-grid-layout initial layout (300ms)
  useLayoutEffect(() => {
    const timer = setTimeout(() => setLayoutSettled(true), 300);
    return () => clearTimeout(timer);
  }, []);

  useEffect(() => {
    if (!layoutSettled || !containerRef.current) return;
    const observer = new IntersectionObserver(
      ([entry]) => setIsVisible(entry.isIntersecting),
      { threshold: 0.1 }
    );
    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [layoutSettled, containerRef]);

  return isVisible;
}
