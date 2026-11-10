/**
 * Identity and access module: sign-in, password, the session scope (top-bar office and role
 * selectors), and the administration screens A-01 … A-04, A-06.
 * Other modules and app/ import only from here (enforced by ESLint, eslint.config.mjs).
 */
export { AccessGroups } from './components/AccessGroups';
export { Assignments } from './components/Assignments';
export { ChangePassword } from './components/ChangePassword';
export { LoginPage } from './components/LoginPage';
export { RolesMatrix } from './components/RolesMatrix';
export { PeriodSwitcher } from './components/PeriodSwitcher';
export { ScopeSwitcher } from './components/ScopeSwitcher';
export { SignInSecurity } from './components/SignInSecurity';
export { UsersAdmin } from './components/UsersAdmin';
export { roleInitials } from './business/scopeRules';
export { useKeepAlive, useRefreshSession, useSignOut, useSwitchScope } from './orchestration/authQueries';
export { useStepUp } from './orchestration/stepUp';
export { needsStepUp } from './business/stepUpRules';
