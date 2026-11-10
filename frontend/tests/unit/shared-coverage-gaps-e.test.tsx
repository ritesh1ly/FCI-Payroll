import { act, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFile, onStepUpRequired } from '@/shared/api';
import { I18nProvider } from '@/shared/i18n';
import { ToastProvider } from '@/shared/ui';
import { BRAND } from '@/shared/ui/brand';
import { downloadFile, usePdfDownload } from '@/shared/ui/download';
import { SimulateAct } from '@/shared/ui/masterParts';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { RowActs, Toolbar, ViewDialog } from '@/shared/ui/wireframe';
import { backend, me, referenceLists, t, wrap } from './support/harness';

vi.mock('next/headers', () => ({
  cookies: async () => ({ getAll: () => [], has: () => false }),
  headers: async () => ({ get: () => null }),
}));

/**
 * Coverage of the shared building blocks' remaining paths: the brand values (OD-121 theme), the document download's
 * fallback name, its Retry-After countdown and its clear (OD-144, OD-149), a step-up the user declines (SRS-G-48), the
 * order copy card's one-file and view-only notices (SRS-G-30), the drawn toolbar, row actions and view dialog, and the
 * sign-in options never kept when the backend refuses them.
 */
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  onStepUpRequired(null);
});

function stubSave() {
  vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined });
  const names: string[] = [];
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    names.push(this.download);
  });
  return { names, restore: () => click.mockRestore() };
}

describe('brand and downloads', () => {
  it('OD_121_theBrandColoursAreThoseOfTheStylesheet', () => {
    expect(BRAND).toEqual({ green: '#17693a', gold: '#d6a531', navy: '#16284a', page: '#f4faf5' });
  });

  it('OD_144_aFileTheServerDoesNotNameIsSavedUnderTheFallbackName', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('%PDF-', { status: 200 })),
    );
    const saved = stubSave();
    try {
      await downloadFile('/v1/x/pdf', 'fallback.pdf');
      expect(saved.names).toEqual(['fallback.pdf']);
    } finally {
      saved.restore();
    }
  });

  it('OD_149_aRetryAfterBlocksTheNextPressUntilItHasCountedDown', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const fetched = vi.fn(
      async () =>
        new Response(JSON.stringify({ errorCode: 'PDF-429-BUSY' }), {
          status: 429,
          headers: { 'Retry-After': '2' },
        }),
    );
    vi.stubGlobal('fetch', fetched);
    const { result } = renderHook(() => usePdfDownload());
    await act(async () => {
      expect(await result.current.download('/v1/x/pdf', 'x.pdf')).toBe(false);
    });
    expect(result.current.retryIn).toBe(2);
    expect(result.current.error).toBeInstanceOf(ApiError);
    // While it counts down a press sends nothing.
    await act(async () => {
      expect(await result.current.download('/v1/x/pdf', 'x.pdf')).toBe(false);
    });
    expect(fetched).toHaveBeenCalledTimes(1);
    await act(async () => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current.retryIn).toBe(1);
    await act(async () => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current.retryIn).toBe(0);
    act(() => result.current.clear());
    expect(result.current.error).toBeNull();
  });

  it('SRS_G_48_aDeclinedStepUpAndNoHandlerBothRefuseTheFile', async () => {
    const refusal = () => new Response(JSON.stringify({ errorCode: 'AUTH-403-STEP-UP' }), { status: 403 });
    const fetched = vi.fn(async () => refusal());
    vi.stubGlobal('fetch', fetched);
    onStepUpRequired(async () => false);
    await expect(apiFile('/v1/x/pdf')).rejects.toMatchObject({ code: 'AUTH-403-STEP-UP' });
    expect(fetched).toHaveBeenCalledTimes(1);
    onStepUpRequired(null);
    await expect(apiFile('/v1/x/pdf')).rejects.toMatchObject({ code: 'AUTH-403-STEP-UP' });
    expect(fetched).toHaveBeenCalledTimes(2);
  });

  it('SRS_G_13_theSignInOptionsAreNotKeptWhenTheBackendRefusesThem', async () => {
    vi.resetModules();
    const { serverLoginOptions } = await import('@/shared/server/backend');
    const fetched = vi.fn(async () => new Response('{}', { status: 503 }));
    vi.stubGlobal('fetch', fetched);
    expect(await serverLoginOptions()).toBeNull();
    expect(await serverLoginOptions()).toBeNull();
    expect(fetched).toHaveBeenCalledTimes(2);
  });
});

function shell(children: React.ReactNode) {
  return render(
    <I18nProvider>
      <ToastProvider>{children}</ToastProvider>
    </I18nProvider>,
  );
}

describe('the drawn parts', () => {
  it('OD_121_theToolbarDrawsThePlainExportAndTheRowEditIsViewOnlyByDefault', async () => {
    const onExport = vi.fn();
    const onClose = vi.fn();
    shell(
      <>
        <Toolbar onExport={onExport} />
        <RowActs onView={() => undefined} viewName="View X" editName="Edit X" />
        <ViewDialog screen="M-17" rows={[{ k: 'Key', v: 'Value', s: 'SYS' }]} onClose={onClose} />
      </>,
    );
    await userEvent.click(screen.getByRole('button', { name: t('wf.export') }));
    expect(onExport).toHaveBeenCalledTimes(1);
    const edit = screen.getByRole('button', { name: 'Edit X' });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('wf.viewOnly'));
    expect(edit).toHaveAttribute('title', t('wf.editOff', [t('wf.viewOnly')]));
    const dialog = screen.getByRole('dialog');
    fireEvent.keyDown(dialog, { key: 'Enter' });
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('SRS_19_4_simulateLinksToC09ForARoleThatMayRunIt', () => {
    backend({});
    wrap(<SimulateAct label="Simulate" />, me(['C-09:VIEW']));
    expect(screen.getByRole('link', { name: 'Simulate' })).toHaveAttribute(
      'href',
      expect.stringContaining('?s=C-09'),
    );
  });
});

describe('the order copy card', () => {
  const pdf = (name: string) => new File(['%PDF'], name, { type: 'application/pdf' });

  function card(over: Partial<React.ComponentProps<typeof OrderCopyCard>> = {}) {
    backend({
      'GET /v1/reference/lists': () => referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']] }),
    });
    const onAttach = vi.fn(async () => undefined);
    wrap(
      <OrderCopyCard
        hint="hint"
        canAttach
        stored={[
          {
            id: 1,
            fileName: 'untyped.pdf',
            documentTypeCode: null,
            documentTypeName: null,
            referenceNumber: null,
            uploadedBy: 'maker@fci-demo.test',
            uploadedAt: '2026-10-01T10:00:00Z',
          },
        ]}
        storedError={null}
        fileHref={(id) => `/files/${id}`}
        today="2026-11-01"
        attachmentRequired={false}
        referenceRequired={() => false}
        onAttach={onAttach}
        {...over}
      />,
      me([]),
    );
    const root = screen.getByText(t('wf.ord.title')).closest('.card') as HTMLElement;
    const files = root.querySelector('input[type="file"]') as HTMLInputElement;
    return { root, files, onAttach };
  }

  it('SRS_G_30_anOrderWithoutTypeOrReferenceReadsDashes', async () => {
    const { root } = card();
    const row = within(root).getByText('untyped.pdf').closest('tr') as HTMLElement;
    const cells = row.querySelectorAll('td');
    expect(cells[1]).toHaveTextContent('—');
    expect(cells[2]).toHaveTextContent('—');
  });

  it('SRS_G_30_aHeldOrderTakesOneFileOnly', async () => {
    const { root, files, onAttach } = card({ holds: true });
    await within(root).findByRole('option', { name: 'Other' });
    const date = root.querySelector('input[type="date"]') as HTMLInputElement;
    fireEvent.change(date, { target: { value: '2026-10-01' } });
    await userEvent.upload(files, [pdf('a.pdf'), pdf('b.pdf')]);
    expect(await within(root).findByText(t('wf.ord.oneFile'))).toBeInTheDocument();
    expect(onAttach).not.toHaveBeenCalled();
    await userEvent.upload(files, pdf('a.pdf'));
    await waitFor(() => expect(onAttach).toHaveBeenCalledTimes(1));
    expect(await within(root).findByText(t('wf.ord.held', ['a.pdf']))).toBeInTheDocument();
  });

  it('SRS_G_30_aRecordThatTakesNoOrderSaysSoWhenAFileIsPicked', async () => {
    const { root, files, onAttach } = card({ canAttach: false, readOnly: false });
    await userEvent.upload(files, pdf('a.pdf'));
    expect(within(root).getByRole('status')).toHaveTextContent(t('wf.ord.viewOnly'));
    expect(onAttach).not.toHaveBeenCalled();
    expect(files.value).toBe('');
  });
});
