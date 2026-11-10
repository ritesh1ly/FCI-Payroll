/**
 * Audit module (A-05 Audit Trail = X-04 Audit Trail & Change History). Public API: the screen and
 * the History button every master screen shows (SRS-20-29).
 * Other modules and app/ import only from here (enforced by ESLint, eslint.config.mjs).
 */
export { AuditTrail } from './components/AuditTrail';
export { HistoryButton } from './components/RecordHistory';
