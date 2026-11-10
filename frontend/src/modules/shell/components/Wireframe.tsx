'use client';

import { createElement, type ReactNode, useCallback, useRef } from 'react';

/** A sanitised design-pack element: text, or [tag, attributes, children] (scripts/build-wireframes.mjs). */
export type WfNode = string | [string, Record<string, string>, WfNode[]];

/** Re-checked here although the build already filtered: nothing outside these lists is rendered. */
const TAGS = new Set([
  'div',
  'span',
  'b',
  'i',
  'em',
  'strong',
  'small',
  'p',
  'h3',
  'h4',
  'code',
  'br',
  'table',
  'thead',
  'tbody',
  'tfoot',
  'tr',
  'th',
  'td',
  'button',
  'select',
  'option',
  'input',
  'label',
  'textarea',
  'ul',
  'li',
]);
const VOID = new Set(['br', 'input']);
const ATTRIBUTES: Record<string, string> = {
  class: 'className',
  id: 'id',
  title: 'title',
  colspan: 'colSpan',
  rowspan: 'rowSpan',
  placeholder: 'placeholder',
  type: 'type',
  name: 'name',
  for: 'htmlFor',
  maxlength: 'maxLength',
  min: 'min',
  max: 'max',
  step: 'step',
  'data-act': 'data-act',
  'data-change': 'data-change',
};

export interface WireframeHandlers {
  onAction: (act: string, args: string[], element: HTMLElement) => void;
}

function render(node: WfNode, key: number): ReactNode {
  if (typeof node === 'string') return node;
  const [tag, attrs, children] = node;
  if (!TAGS.has(tag)) return null;
  const props: Record<string, unknown> = { key };
  for (const [name, value] of Object.entries(attrs)) {
    const prop = ATTRIBUTES[name];
    if (prop) props[prop] = value;
  }
  if ('value' in attrs) props.defaultValue = attrs.value;
  if ('checked' in attrs) props.defaultChecked = true;
  if ('disabled' in attrs) props.disabled = true;
  if ('readonly' in attrs) props.readOnly = true;
  if (tag === 'button') props.type = 'button';
  if (tag === 'select') {
    const chosen = children.find((c) => Array.isArray(c) && c[0] === 'option' && 'selected' in c[1]);
    if (Array.isArray(chosen)) props.defaultValue = chosen[1].value ?? textOf(chosen);
  }
  if (tag === 'option' && !('value' in attrs)) props.value = textOf(node);
  return VOID.has(tag) ? createElement(tag, props) : createElement(tag, props, children.map(render));
}

function textOf(node: WfNode): string {
  return typeof node === 'string' ? node : node[2].map(textOf).join('');
}

/**
 * Renders a wireframe body with React (never innerHTML) inside the live `.wireframe-body-container`.
 * Clicks and changes on elements carrying a data-act / data-change descriptor are delegated to
 * {@link WireframeHandlers}; the live list search (`.lsearch input`) filters the table rows.
 */
export function Wireframe({
  nodes,
  handlers,
  className = 'wireframe-body-container',
}: {
  nodes: WfNode[];
  handlers: WireframeHandlers;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);

  const dispatch = useCallback(
    (target: EventTarget | null, attribute: 'data-act' | 'data-change') => {
      const element = (target as HTMLElement | null)?.closest?.(`[${attribute}]`) as HTMLElement | null;
      if (!element || !ref.current?.contains(element)) return;
      try {
        const descriptor = JSON.parse(element.getAttribute(attribute) ?? '') as {
          act: string;
          args: string[];
        };
        const args = descriptor.args.map((a) =>
          a === 'this.value' ? ((element as HTMLInputElement).value ?? '') : a,
        );
        handlers.onAction(descriptor.act, args, element);
      } catch {
        // A malformed descriptor is ignored: the build only emits valid ones.
      }
    },
    [handlers],
  );

  const search = useCallback((target: EventTarget | null) => {
    const input = target as HTMLInputElement | null;
    if (!input?.closest?.('.lsearch') || !ref.current) return;
    const needle = input.value.toLowerCase().trim();
    ref.current.querySelectorAll<HTMLTableRowElement>('table.t tbody tr').forEach((row) => {
      row.hidden = !(row.textContent ?? '').toLowerCase().includes(needle);
    });
  }, []);

  return (
    // The wireframe is an interactive mock-up: its own buttons and inputs carry the semantics.
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions, jsx-a11y/click-events-have-key-events
    <div
      ref={ref}
      className={`${className} wf-root`}
      onClick={(e) => dispatch(e.target, 'data-act')}
      onChange={(e) => dispatch(e.target, 'data-change')}
      onInput={(e) => search(e.target)}
    >
      {nodes.map(render)}
    </div>
  );
}

/** The live setM1Filter: shows only rows whose status pill contains the filter text. */
export function filterRowsByStatus(container: HTMLElement | null, status: string): void {
  container?.querySelectorAll<HTMLTableRowElement>('table.t tbody tr').forEach((row) => {
    const pill = row.querySelector('.pill')?.textContent ?? '';
    row.hidden = Boolean(status) && !pill.toLowerCase().includes(status.toLowerCase());
  });
}
