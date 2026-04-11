import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { python } from '@codemirror/lang-python';
import { sql } from '@codemirror/lang-sql';
import { bracketMatching } from '@codemirror/language';
import { Compartment,EditorState } from '@codemirror/state';
import { Decoration, type DecorationSet, EditorView, keymap,lineNumbers, MatchDecorator, ViewPlugin,type ViewUpdate } from '@codemirror/view';
import { useTheme } from 'next-themes';
import { useEffect, useRef } from 'react';

const stepRefMatcher = new MatchDecorator({
  regexp: /\{\{#\d+\}\}/g,
  decoration: Decoration.mark({ class: 'cm-step-ref' }),
});

const stepRefHighlighter = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet;
    constructor(view: EditorView) {
      this.decorations = stepRefMatcher.createDeco(view);
    }
    update(update: ViewUpdate) {
      this.decorations = stepRefMatcher.updateDeco(update, this.decorations);
    }
  },
  { decorations: (v) => v.decorations },
);

interface ScriptEditorProps {
  value: string;
  onChange: (value: string) => void;
  language: 'SQL' | 'PYTHON';
  readOnly?: boolean;
  insertTextRef?: React.MutableRefObject<((text: string) => void) | null>;
}

function buildStepRefTheme(isDark: boolean) {
  return EditorView.theme({
    '&': { minHeight: '200px', height: '100%', ...(isDark ? { backgroundColor: 'oklch(0.13 0.015 280)' } : {}) },
    '.cm-scroller': { overflow: 'auto' },
    '.cm-content': { fontFamily: 'monospace', fontSize: '13px' },
    '.cm-gutters': isDark ? { backgroundColor: 'oklch(0.15 0.015 280)' } : {},
    '.cm-activeLineGutter': isDark ? { backgroundColor: 'oklch(1 0 0 / 5%)' } : {},
    '.cm-activeLine': isDark ? { backgroundColor: 'oklch(1 0 0 / 5%)' } : {},
    '.cm-step-ref': {
      background: isDark ? 'rgba(167, 139, 250, 0.22)' : 'rgba(124, 58, 237, 0.15)',
      borderRadius: '3px',
      padding: '1px 2px',
      fontWeight: '600',
    },
  });
}

export default function ScriptEditor({ value, onChange, language, readOnly = false, insertTextRef }: ScriptEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const languageCompartment = useRef(new Compartment());
  const readOnlyCompartment = useRef(new Compartment());
  const themeCompartment = useRef(new Compartment());
  const onChangeRef = useRef(onChange);
  const { resolvedTheme } = useTheme();

  // Keep onChange ref current without recreating editor
  onChangeRef.current = onChange;

  // Create editor on mount
  useEffect(() => {
    if (!containerRef.current) return;

    const langExtension = language === 'SQL' ? sql() : python();

    const state = EditorState.create({
      doc: value,
      extensions: [
        lineNumbers(),
        history(),
        bracketMatching(),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        languageCompartment.current.of(langExtension),
        readOnlyCompartment.current.of([
          EditorView.editable.of(!readOnly),
          EditorState.readOnly.of(!!readOnly),
        ]),
        stepRefHighlighter,
        EditorView.updateListener.of((update) => {
          if (!readOnly && update.docChanged) {
            onChangeRef.current(update.state.doc.toString());
          }
        }),
        themeCompartment.current.of(buildStepRefTheme(resolvedTheme === 'dark')),
      ],
    });

    const view = new EditorView({ state, parent: containerRef.current });
    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Bind insertText function to ref
  useEffect(() => {
    if (!insertTextRef) return;
    insertTextRef.current = (text: string) => {
      const view = viewRef.current;
      if (!view) return;
      const cursor = view.state.selection.main.head;
      view.dispatch({ changes: { from: cursor, insert: text } });
      view.focus();
    };
    return () => {
      if (insertTextRef) insertTextRef.current = null;
    };
  }, [insertTextRef]);

  // Reconfigure language when it changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const langExtension = language === 'SQL' ? sql() : python();
    view.dispatch({ effects: languageCompartment.current.reconfigure(langExtension) });
  }, [language]);

  // Reconfigure readOnly when it changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dispatch({
      effects: readOnlyCompartment.current.reconfigure([
        EditorView.editable.of(!readOnly),
        EditorState.readOnly.of(!!readOnly),
      ]),
    });
  }, [readOnly]);

  // Reconfigure theme when dark/light changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dispatch({ effects: themeCompartment.current.reconfigure(buildStepRefTheme(resolvedTheme === 'dark')) });
  }, [resolvedTheme]);

  // Sync external value changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const current = view.state.doc.toString();
    if (current !== value) {
      view.dispatch({
        changes: { from: 0, to: current.length, insert: value },
      });
    }
  }, [value]);

  return (
    <div
      ref={containerRef}
      className="border rounded-md overflow-hidden w-full min-w-0"
      style={{ minHeight: '200px', maxWidth: '100%' }}
    />
  );
}
