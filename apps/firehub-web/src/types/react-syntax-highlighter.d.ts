declare module 'react-syntax-highlighter/dist/esm/prism-light' {
  import type { ComponentType } from 'react';
  interface SyntaxHighlighterProps {
    language?: string;
    style?: Record<string, React.CSSProperties>;
    children?: string;
    className?: string;
    [key: string]: unknown;
  }
  const SyntaxHighlighter: ComponentType<SyntaxHighlighterProps> & {
    registerLanguage: (name: string, lang: unknown) => void;
};
  export default SyntaxHighlighter;
}

declare module 'react-syntax-highlighter/dist/esm/languages/prism/sql' {
  const lang: unknown;
  export default lang;
}

declare module 'react-syntax-highlighter/dist/esm/languages/prism/python' {
  const lang: unknown;
  export default lang;
}

declare module 'react-syntax-highlighter/dist/esm/languages/prism/javascript' {
  const lang: unknown;
  export default lang;
}

declare module 'react-syntax-highlighter/dist/esm/languages/prism/typescript' {
  const lang: unknown;
  export default lang;
}

declare module 'react-syntax-highlighter/dist/esm/languages/prism/json' {
  const lang: unknown;
  export default lang;
}

declare module 'react-syntax-highlighter/dist/esm/languages/prism/bash' {
  const lang: unknown;
  export default lang;
}

declare module 'react-syntax-highlighter/dist/esm/styles/prism' {
  const styles: Record<string, Record<string, React.CSSProperties>>;
  export const oneDark: Record<string, React.CSSProperties>;
  export default styles;
}
