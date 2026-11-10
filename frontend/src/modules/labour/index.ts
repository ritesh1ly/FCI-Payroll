/**
 * Labour module (M-02 Labour Master, M-09 Gang / Shed Structure, I-01 Labour Attendance Capture, I-02 Work-Slip Capture, M-06 Incentive Norms;
 * plan 20). Public API: the screens.
 * Other modules and app/ import only from here (enforced by ESLint, eslint.config.mjs).
 */
export { LabourMaster } from './components/LabourMasterScreen';
export { GangStructure } from './components/GangStructureScreen';
export { AttendanceCapture } from './components/AttendanceScreen';
export { WorkSlips } from './components/WorkSlipScreen';
export { IncentiveNorms } from './components/IncentiveNormsScreen';
export { LabourRates } from './components/LabourRatesScreen';
export { DepartmentalComputation, DpsComputation, NwnpComputation } from './components/LabourComputation';
export { IncentiveComputation } from './components/IncentiveComputation';
