import { dehydrate, HydrationBoundary, QueryClient } from '@tanstack/react-query';
import { redirect } from 'next/navigation';
import { LoginPage } from '@/modules/iam';
import { serverLoginOptions, serverMe } from '@/shared/server';

export const dynamic = 'force-dynamic';

/**
 * Login, rendered on the server: a signed-in caller goes straight to its landing route; everyone
 * else gets the page with the persona and location options already in it.
 */
export default async function Home() {
  const me = await serverMe();
  if (me) redirect(me.route);
  const queryClient = new QueryClient();
  const options = await serverLoginOptions();
  if (options) queryClient.setQueryData(['login-options'], options);
  return (
    <HydrationBoundary state={dehydrate(queryClient)}>
      <LoginPage />
    </HydrationBoundary>
  );
}
