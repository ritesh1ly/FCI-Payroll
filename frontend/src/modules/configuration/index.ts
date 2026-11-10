/**
 * Configuration module: A-07 System Parameters and the dynamic master screens on the rule engine of
 * target DDL v4.0 (M-04, M-22 rate sets; M-23 other parameters; M-24 GL mapping; M-16 reference lists) and the Module 04
 * masters (M-14 net-pay floors, M-15 ceiling norms, M-25 circulars, M-26 labels, M-27 operations) and the Module 06
 * M-07 component catalogue and M-08 rule catalogue. Public API: the
 * screens only. Other modules and app/ import only from here (enforced by ESLint, eslint.config.mjs).
 */
export { CeilingNorms } from './components/CeilingNorms';
export { CeilingNormsScreen } from './components/CeilingNormsScreen';
export { ComponentCatalogue } from './components/ComponentCatalogue';
export { Circulars } from './components/Circulars';
export { CurrentCircularsHome } from './components/CurrentCircularsHome';
export { GlMapping } from './components/AccountingMapping';
export { Labels } from './components/Labels';
export { NetPayFloors } from './components/NetPayFloors';
export { PenaltyRulesScreen } from './components/PenaltyRulesScreen';
export { orderCapturePanel } from '@/shared/ui/wireframe';
export { Operations } from './components/Operations';
export { OtherParameters } from './components/OtherParametersScreen';
export { ReferenceLists } from './components/ReferenceLists';
export { RuleCatalogue } from './components/RuleCatalogue';
export { SpecialRates } from './components/SpecialRateTables';
export { StaffRates } from './components/StaffRateTables';
export { SystemParameters } from './components/SystemParameters';
