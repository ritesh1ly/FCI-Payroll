import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { useMediaQuery } from '@/shared/ui/media';

/**
 * Server rendering of a media-dependent layout: the server answers the given value (never the
 * client's window), so the first client render hydrates the same markup.
 */
function Probe({ serverValue }: { serverValue?: boolean }) {
  return <span>{useMediaQuery('(max-width: 900px)', serverValue) ? 'compact' : 'wide'}</span>;
}

describe('useMediaQuery on the server', () => {
  it('answers the server value, wide by default', () => {
    expect(renderToString(<Probe />)).toContain('wide');
    expect(renderToString(<Probe serverValue />)).toContain('compact');
  });
});
