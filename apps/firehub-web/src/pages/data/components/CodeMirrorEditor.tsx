import { useRef, useEffect } from 'react';
import { EditorState } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { sql, PostgreSQL } from '@codemirror/lang-sql';
import { oneDark } from '@codemirror/theme-one-dark';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { searchKeymap } from '@codemirror/search';
import { autocompletion } from '@codemirror/autocomplete';

interface CodeMirrorEditorProps {
  value: string;
  onChange: (value: string) => void;
  onExecute: () => void;
  columnNames: string[];
}

export function CodeMirrorEditor({ value, onChange, onExecute, columnNames }: CodeMirrorEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const onExecuteRef = useRef(onExecute);

  // Keep refs up to date
  onChangeRef.current = onChange;
  onExecuteRef.current = onExecute;

  useEffect(() => {
    if (!containerRef.current) return;

    const schema: Record<string, string[]> = {
      // Provide column names as table completion context
      '': columnNames,
    };

    const state = EditorState.create({
      doc: value,
      extensions: [
        sql({ dialect: PostgreSQL, schema }),
        oneDark,
        history(),
        autocompletion(),
        keymap.of([
          ...defaultKeymap,
          ...historyKeymap,
          ...searchKeymap,
          {
            key: 'Mod-Enter',
            run: () => {
              onExecuteRef.current();
              return true;
            },
          },
        ]),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            onChangeRef.current(update.state.doc.toString());
          }
        }),
        EditorView.theme({
          '&': {
            fontSize: '13px',
            border: '1px solid hsl(var(--border))',
            borderRadius: '6px',
            cursor: 'text',
          },
          '.cm-editor': {
            minHeight: '150px',
            maxHeight: '300px',
          },
          '.cm-scroller': {
            overflow: 'auto',
            minHeight: '150px',
            maxHeight: '300px',
          },
          '.cm-content': {
            minHeight: '140px',
          },
        }),
      ],
    });

    const view = new EditorView({
      state,
      parent: containerRef.current,
    });

    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // Only run on mount; value changes are handled externally
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Sync external value changes into the editor
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const currentDoc = view.state.doc.toString();
    if (currentDoc !== value) {
      view.dispatch({
        changes: { from: 0, to: currentDoc.length, insert: value },
      });
    }
  }, [value]);

  return <div ref={containerRef} />;
}
