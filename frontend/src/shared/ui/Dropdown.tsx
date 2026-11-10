'use client';

import { Check, ChevronDown } from 'lucide-react';
import { type KeyboardEvent as ReactKeyboardEvent, type ReactNode, useEffect, useRef, useState } from 'react';

export interface DropdownOption {
  value: string;
  label: string;
  subLabel?: string;
}

const VARIANT = {
  topbar: (disabled: boolean, open: boolean) =>
    `px-2.5 py-1 rounded-lg text-[11px] font-semibold flex items-center gap-1.5 transition-all select-none cursor-pointer border bg-gradient-to-b from-white to-slate-50/90 shadow-[0_1px_2px_rgba(0,0,0,0.04),inset_0_1px_0_rgba(255,255,255,0.8)] active:scale-[0.98] ${disabled ? 'opacity-50 cursor-not-allowed bg-slate-50 border-slate-200 text-slate-400' : open ? 'bg-white border-green-900 text-green-950 shadow-xs ring-2 ring-green-900/15' : 'border-slate-200/90 text-slate-700 hover:bg-slate-50 hover:border-slate-300 hover:shadow-xs'}`,
  form: (disabled: boolean, open: boolean) =>
    `w-full px-3 py-1.5 rounded-xl text-xs font-medium flex items-center justify-between gap-2 transition-all select-none border bg-white shadow-[0_1px_2px_rgba(0,0,0,0.04)] ${disabled ? 'cursor-not-allowed bg-slate-50 border-slate-200 text-slate-400' : open ? 'border-green-900 text-green-950 ring-2 ring-green-900/15 shadow-xs cursor-pointer' : 'border-slate-200 text-slate-800 hover:border-slate-300 cursor-pointer'}`,
} as const;

/**
 * The live workspace dropdown (top bar period / scope / role), ported markup and classes.
 *
 * SRS-G-54 (US-GAP-111) keyboard operation: Arrow Down / Up on the button open the list; in the list the
 * arrows, Home and End move between the options (focus starts on the selected one), Enter or Space
 * chooses, Escape closes and gives the focus back to the button, Tab closes. The selected option is
 * marked by aria-selected and a check, never by colour alone.
 */
export function Dropdown({
  value,
  onChange,
  options,
  placeholder,
  disabled = false,
  className = '',
  align = 'left',
  icon,
  variant = 'topbar',
  label,
}: {
  value: string;
  onChange: (value: string) => void;
  options: DropdownOption[];
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  align?: 'left' | 'right';
  icon?: ReactNode;
  variant?: keyof typeof VARIANT;
  label: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const selected = options.find((o) => o.value === value);

  /** The option buttons of the open list, in order. */
  function optionButtons(): HTMLButtonElement[] {
    return Array.from(list.current?.querySelectorAll<HTMLButtonElement>('[role="option"]') ?? []);
  }

  // Opened: the focus moves to the selected option (else the first), so the arrows work at once.
  useEffect(() => {
    if (!open) return;
    const buttons = Array.from(list.current?.querySelectorAll<HTMLButtonElement>('[role="option"]') ?? []);
    (buttons.find((b) => b.getAttribute('aria-selected') === 'true') ?? buttons[0])?.focus();
  }, [open]);

  function close(refocus: boolean) {
    setOpen(false);
    if (refocus) trigger.current?.focus();
  }

  function onTriggerKey(event: ReactKeyboardEvent<HTMLButtonElement>) {
    if (disabled || open) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setOpen(true);
    }
  }

  function onListKey(event: ReactKeyboardEvent<HTMLDivElement>) {
    const buttons = optionButtons();
    const at = buttons.indexOf(document.activeElement as HTMLButtonElement);
    let next: number | null = null;
    if (event.key === 'ArrowDown') next = at < 0 ? 0 : Math.min(at + 1, buttons.length - 1);
    else if (event.key === 'ArrowUp') next = at < 0 ? buttons.length - 1 : Math.max(at - 1, 0);
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = buttons.length - 1;
    else if (event.key === 'Escape') {
      event.preventDefault();
      close(true);
      return;
    } else if (event.key === 'Tab') {
      close(false);
      return;
    }
    const target = next === null ? undefined : buttons[next];
    if (target) {
      event.preventDefault();
      target.focus();
    }
  }

  useEffect(() => {
    if (!open) return undefined;
    function outside(event: MouseEvent) {
      if (ref.current && !ref.current.contains(event.target as Node)) setOpen(false);
    }
    function escape(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
        trigger.current?.focus();
      }
    }
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  return (
    <div ref={ref} className="relative inline-block text-left">
      <button
        ref={trigger}
        type="button"
        disabled={disabled}
        onKeyDown={onTriggerKey}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={label}
        onClick={() => !disabled && setOpen(!open)}
        className={`${VARIANT[variant](disabled, open)} ${className}`}
      >
        <div className="flex items-center gap-1.5 min-w-0 truncate">
          {icon && <span className="shrink-0">{icon}</span>}
          <span className="truncate tracking-tight">{selected ? selected.label : (placeholder ?? '')}</span>
        </div>
        <ChevronDown
          className={`w-3.5 h-3.5 text-slate-400 shrink-0 ml-1 transition-colors ${open ? 'text-green-900' : ''}`}
        />
      </button>
      {open && (
        <div
          ref={list}
          role="listbox"
          aria-label={label}
          tabIndex={-1}
          onKeyDown={onListKey}
          className={`absolute z-50 mt-1.5 min-w-[150px] max-w-xs max-h-64 overflow-y-auto p-1.5 bg-white/90 backdrop-blur-xl backdrop-saturate-150 rounded-2xl shadow-[0_16px_40px_rgba(0,0,0,0.12),0_2px_6px_rgba(0,0,0,0.04)] border border-black/[0.08] ring-1 ring-black/[0.03] text-xs ${align === 'right' ? 'right-0 origin-top-right' : 'left-0 origin-top-left'}`}
        >
          {options.map((option) => {
            const isSelected = option.value === value;
            return (
              <button
                key={option.value}
                type="button"
                role="option"
                aria-selected={isSelected}
                onClick={() => {
                  onChange(option.value);
                  close(true);
                }}
                className={`w-full text-left px-2.5 py-1.5 rounded-lg flex items-center justify-between gap-2.5 transition-all duration-100 group/item cursor-pointer ${isSelected ? 'bg-green-100/90 text-green-950 font-bold hover:bg-green-950 hover:text-white' : 'text-slate-700 font-medium hover:bg-green-950 hover:text-white'}`}
              >
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[11.5px] tracking-tight">{option.label}</div>
                  {option.subLabel && (
                    <div
                      className={`text-[9.5px] truncate transition-colors ${isSelected ? 'text-slate-500 group-hover/item:text-green-300' : 'text-slate-400 group-hover/item:text-green-300'}`}
                    >
                      {option.subLabel}
                    </div>
                  )}
                </div>
                {isSelected ? (
                  <Check className="w-3.5 h-3.5 text-green-950 group-hover/item:text-white shrink-0 ml-1" />
                ) : (
                  <span className="w-3.5 shrink-0" />
                )}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
