import { withBase } from '@/shared/ui';

/**
 * The design-pack body of a wireframe screen, from the Next.js server route that checks the
 * session's VIEW permission first (/payroll/wireframe/<screen>). Never bundled in the browser.
 */
export const wireframeApi = {
  body: async (screen: string): Promise<unknown[]> => {
    const response = await fetch(withBase(`/payroll/wireframe/${encodeURIComponent(screen)}`), {
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) throw new Error(`wireframe ${screen}: ${response.status}`);
    return (await response.json()) as unknown[];
  },
};
