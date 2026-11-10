import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import wireframes from '@/generated/wireframes.json';
import { filterRowsByStatus, type WfNode, Wireframe } from '@/modules/shell/components/Wireframe';
// @ts-expect-error -- plain JavaScript build script
import { parse } from '../../scripts/build-wireframes.mjs';

describe('wireframe sanitisation', () => {
  it('turns handlers into descriptors, styles into classes and drops everything else', () => {
    const nodes = parse(
      `<div class="card" style="color:red" onmouseover="steal()"><button onclick="doAct('Export')">⇩ Export</button>` +
        `<script>alert(1)</script><a href="javascript:x">x</a><img src=x onerror=alert(1)>` +
        `<select onchange="setM1Filter(this.value)"><option value="">All</option></select>&amp;</div>`,
    ) as WfNode[];
    const [div] = nodes as [[string, Record<string, string>, WfNode[]]];
    expect(div[0]).toBe('div');
    expect(div[1].class).toMatch(/^card wf-s\d+$/);
    expect(div[1]).not.toHaveProperty('onmouseover');
    const button = div[2][0] as [string, Record<string, string>, WfNode[]];
    expect(JSON.parse(button[1]['data-act'] ?? '')).toEqual({ act: 'doAct', args: ['Export'] });
    const json = JSON.stringify(nodes);
    expect(json).not.toContain('alert');
    expect(json).not.toContain('javascript');
    expect(json).not.toContain('onerror');
    expect(json).toContain('"&"');
  });

  it('ships every live screen with sanitised bodies', () => {
    const all = wireframes as Record<string, { code: string; bodies: Record<string, WfNode[]> }>;
    expect(Object.values(all).map((s) => s.code)).toEqual([
      'M-01',
      'M-04',
      'M-22',
      'M-23',
      'M-24',
      'I-17',
      'M-11',
      'C-02',
    ]);
    const text = JSON.stringify(all);
    expect(text).not.toMatch(/onclick|onchange|style=|<script/i);
  });
});

describe('the wireframe renderer', () => {
  const nodes: WfNode[] = [
    ['div', { class: 'lsearch' }, [['input', { placeholder: 'Search', type: 'text' }, []]]],
    ['button', { 'data-act': JSON.stringify({ act: 'doAct', args: ['Bulk update'] }) }, ['Bulk update']],
    [
      'select',
      { 'data-change': JSON.stringify({ act: 'setM1Filter', args: ['this.value'] }) },
      [['option', { value: 'Active', selected: '' }, ['Active']]],
    ],
    [
      'table',
      { class: 't' },
      [
        [
          'tbody',
          {},
          [
            ['tr', {}, [['td', {}, ['104882 ', ['span', { class: 'pill' }, ['Active']]]]]],
            ['tr', {}, [['td', {}, ['105461 ', ['span', { class: 'pill' }, ['Change pending']]]]]],
          ],
        ],
      ],
    ],
    ['marquee', {}, ['ignored']],
  ];

  it('renders allowed tags only and delegates actions', () => {
    const onAction = vi.fn();
    const { container } = render(<Wireframe nodes={nodes} handlers={{ onAction }} />);
    expect(container.querySelector('marquee')).toBeNull();
    expect(container.firstElementChild).toHaveClass('wireframe-body-container', 'wf-root');
    fireEvent.click(screen.getByRole('button', { name: 'Bulk update' }));
    expect(onAction).toHaveBeenCalledWith('doAct', ['Bulk update'], expect.any(HTMLElement));
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'Active' } });
    expect(onAction).toHaveBeenLastCalledWith('setM1Filter', ['Active'], expect.any(HTMLElement));
  });

  it('filters rows by the live search box and by status', () => {
    const { container } = render(<Wireframe nodes={nodes} handlers={{ onAction: vi.fn() }} />);
    const rows = container.querySelectorAll('tbody tr');
    fireEvent.input(screen.getByPlaceholderText('Search'), { target: { value: '105461' } });
    expect((rows[0] as HTMLElement).hidden).toBe(true);
    expect((rows[1] as HTMLElement).hidden).toBe(false);
    filterRowsByStatus(container, 'active');
    expect((rows[0] as HTMLElement).hidden).toBe(false);
    expect((rows[1] as HTMLElement).hidden).toBe(true);
    filterRowsByStatus(container, '');
    expect((rows[1] as HTMLElement).hidden).toBe(false);
  });

  it('keeps checked, disabled and read-only fields, takes option values from their text and the chosen option', () => {
    const form: WfNode[] = [
      ['input', { type: 'checkbox', checked: '' }, []],
      ['input', { type: 'text', disabled: '', value: 'fixed' }, []],
      ['input', { type: 'text', readonly: '', value: 'ro' }, []],
      [
        'select',
        {},
        [
          ['option', {}, ['All']],
          ['option', { selected: '' }, ['Change pending']],
        ],
      ],
      ['select', {}, [['option', {}, ['Only']]]],
      ['span', { 'data-act': JSON.stringify({ act: 'toast', args: ['this.value'] }) }, ['Ping']],
      ['table', { class: 't' }, [['tbody', {}, [['tr', {}, [['td', {}, ['no pill here']]]]]]]],
      ['input', { placeholder: 'Outside', type: 'text' }, []],
    ];
    const onAction = vi.fn();
    const { container } = render(<Wireframe nodes={form} handlers={{ onAction }} />);
    const [box, fixed, ro] = Array.from(container.querySelectorAll('input'));
    expect(box?.checked).toBe(true);
    expect(fixed?.disabled).toBe(true);
    expect(ro?.readOnly).toBe(true);
    const [withChoice, single] = Array.from(container.querySelectorAll('select'));
    expect(withChoice?.value).toBe('Change pending');
    expect(single?.value).toBe('Only');
    // An element without a value (a span acting as a button) sends the empty argument.
    fireEvent.click(screen.getByText('Ping'));
    expect(onAction).toHaveBeenCalledWith('toast', [''], expect.any(HTMLElement));
    // Typing outside the list search filters nothing; a row without a pill never matches a status.
    fireEvent.input(screen.getByPlaceholderText('Outside'), { target: { value: 'zzz' } });
    const row = container.querySelector('tbody tr') as HTMLElement;
    expect(row.hidden).toBe(false);
    filterRowsByStatus(container, 'active');
    expect(row.hidden).toBe(true);
  });
});
