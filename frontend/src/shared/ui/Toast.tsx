'use client';

import { createContext, type ReactNode, useCallback, useContext, useMemo, useRef, useState } from 'react';

type Notify = (message: string) => void;

const ToastContext = createContext<Notify>(() => undefined);

/** The workspace toast of wireframe v6.1 (dark, bottom right, 3.5 s; styles/workspace.css). */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const notify = useCallback<Notify>((text) => {
    setMessage(text);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setMessage(null), 3500);
  }, []);
  const value = useMemo(() => notify, [notify]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      {message && (
        <div className="fl-toasts">
          <div role="status" className="fl-toast">
            {message}
          </div>
        </div>
      )}
    </ToastContext.Provider>
  );
}

export function useToast(): Notify {
  return useContext(ToastContext);
}
