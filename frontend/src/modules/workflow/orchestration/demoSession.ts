import { QueryClientContext } from '@tanstack/react-query';
import { useContext } from 'react';
import { meKey, type Me } from '@/shared/session';

/**
 * OD-146: whether the session is on a demo stack (the cached "me"), read without subscribing, so the OTP dialog's
 * provider also works where no query client is mounted (it is then false).
 */
export function useDemoSession(): boolean {
  const queries = useContext(QueryClientContext);
  return queries?.getQueryData<Me>(meKey)?.demo === true;
}
