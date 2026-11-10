import { dehydrate, HydrationBoundary, QueryClient } from '@tanstack/react-query';
import { redirect } from 'next/navigation';
import { Workspace } from '@/modules/shell';
import { guardScreen, serverMe, serverOffence } from '@/shared/server';

export const dynamic = 'force-dynamic';

/**
 * Server-side route guard: no session, no workspace; a screen the session may not view (e.g.
 * ?s=C-13 without C-13 VIEW) is never rendered, the workspace opens on its landing screen instead.
 * The profile (permissions, scope) is rendered into the page; the backend authorises every call again.
 */
export default async function Payroll({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const me = await serverMe();
  if (!me) redirect((await serverOffence()) ? '/?signedOut=offence' : '/');
  const requested = (await searchParams).s;
  const target = guardScreen(me, typeof requested === 'string' ? requested : null);
  if (target !== null) redirect(target);
  const queryClient = new QueryClient();
  // Same key as useMe() (lib/session.ts, a client module: its values cannot be imported here).
  queryClient.setQueryData(['me'], me);
  return (
    <HydrationBoundary state={dehydrate(queryClient)}>
      <Workspace />
    </HydrationBoundary>
  );
}
