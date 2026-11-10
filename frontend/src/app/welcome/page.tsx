import { redirect } from 'next/navigation';

/** The former welcome step: sign-in now goes straight to the workspace; old links follow it there. */
export default function Welcome() {
  redirect('/payroll');
}
