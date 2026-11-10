/**
 * General module (Module 01 transactions): X-05 Exceptions & Holds, X-06 Payroll Query, X-07 HRMS
 * Fallback Upload, I-20 Off-Cycle Salary, I-23 Supplementary Payment. Public API: the screens, and the
 * standard order-copy control of SRS-G-30 that other transaction screens reuse (I-22 evidence). Other
 * modules and app/ import only from here (enforced by ESLint, eslint.config.mjs).
 */
export { ComputationExceptions } from './components/ComputationExceptions';
export { ExceptionsWorklist } from './components/ExceptionsWorklist';
export { HrmsFallback } from './components/HrmsFallback';
export { OffCycle } from './components/OffCycle';
export { PayrollQueries } from './components/PayrollQueries';
export { Supplementary } from './components/Supplementary';
export { EMPTY_ORDER, OrderFields, orderComplete, todayIso, type OrderDraft } from './components/OrderFields';
export { isAsciiText, monthName, monthShort } from './business/generalRules';
export { useDocumentTypes } from './orchestration/generalQueries';
