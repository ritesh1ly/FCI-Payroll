import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ClientErrorLogging } from '@/shared/log/ClientErrorLogging';
import { log } from '@/shared/log/logger';

/**
 * R-PII / observability: every exception nothing else caught is logged once with the page path,
 * the error itself when the event carries one, else its message; the listeners leave with the
 * component.
 */

afterEach(() => vi.restoreAllMocks());

describe('ClientErrorLogging', () => {
  it('logs an uncaught error and an unhandled rejection, and stops after unmount', () => {
    const exception = vi.spyOn(log, 'exception').mockImplementation(() => undefined);
    const { container, unmount } = render(<ClientErrorLogging />);
    expect(container.childElementCount).toBe(0);
    const cause = new Error('boom');
    window.dispatchEvent(new ErrorEvent('error', { error: cause, message: 'boom' }));
    expect(exception).toHaveBeenLastCalledWith('ui.uncaught_error', cause, {
      path: window.location.pathname,
    });
    window.dispatchEvent(new ErrorEvent('error', { message: 'Script error.' }));
    expect(exception).toHaveBeenLastCalledWith('ui.uncaught_error', 'Script error.', {
      path: window.location.pathname,
    });
    const rejection = new Event('unhandledrejection') as PromiseRejectionEvent;
    Object.defineProperty(rejection, 'reason', { value: 'denied' });
    window.dispatchEvent(rejection);
    expect(exception).toHaveBeenLastCalledWith('ui.unhandled_rejection', 'denied', {
      path: window.location.pathname,
    });
    unmount();
    exception.mockClear();
    window.dispatchEvent(new ErrorEvent('error', { message: 'after' }));
    window.dispatchEvent(rejection);
    expect(exception).not.toHaveBeenCalled();
  });
});
