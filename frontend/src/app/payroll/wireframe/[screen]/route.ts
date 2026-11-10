import { NextResponse } from 'next/server';
import { serverMe, wireframeBody } from '@/shared/server';

export const dynamic = 'force-dynamic';

/**
 * GET /payroll/wireframe/<screen>: the design-pack body of a wireframe screen, for the session's own
 * role, only if the session may VIEW the screen (deny by default). 401 without a session, 404 for
 * anything else, including a screen the session may not open (no hint that it exists).
 */
export async function GET(_request: Request, { params }: { params: Promise<{ screen: string }> }) {
  const me = await serverMe();
  if (!me) {
    return NextResponse.json(
      { error: 'unauthenticated' },
      { status: 401, headers: { 'Cache-Control': 'no-store' } },
    );
  }
  const body = wireframeBody(me, (await params).screen);
  if (body === null) {
    return NextResponse.json(
      { error: 'not found' },
      { status: 404, headers: { 'Cache-Control': 'no-store' } },
    );
  }
  return NextResponse.json(body, { headers: { 'Cache-Control': 'private, no-store' } });
}
