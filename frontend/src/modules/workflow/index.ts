/**
 * Workflow module (maker-checker): W-01 Approval Inbox, W-02 My Submissions, the OTP step of governed actions (used by every module that submits for approval) and the governed wireframe buttons.
 * Other modules and app/ import only from here (enforced by ESLint, eslint.config.mjs).
 */
export { ApprovalInbox } from './components/ApprovalInbox';
export { GovernedProvider } from './components/Governed';
export { useGoverned } from './orchestration/governed';
export { MySubmissions } from './components/MySubmissions';
export { WorkflowMaster } from './components/WorkflowMaster';
export { useDecision, usePerformAction, useScreenActions } from './orchestration/workflowQueries';
export type { ActionView, Decision, OtpProof } from './api/workflowApi';
