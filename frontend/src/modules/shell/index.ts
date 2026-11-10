/**
 * Application shell: the composition root that assembles every module's screens into the workspace (it may use every module; no module uses it).
 * Other modules and app/ import only from here (enforced by ESLint, eslint.config.mjs).
 */
export { Workspace } from './components/Workspace';
