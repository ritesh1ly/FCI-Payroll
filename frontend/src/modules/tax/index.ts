/**
 * Income Tax, TDS & Professional Tax module (Module 08, docs/plan/08-income-tax.md): the M-18
 * section and PT state masters (08b), the I-07 review and the member's E-01 declarations (08c), I-18 savings and income (08d), C-07
 * computation (08e), M-17 output definitions (08f), C-10 Form 16 and the member's E-01 certificates (08g),
 * C-11 Form 24Q (08h). Public API: the screens and cards only. Other modules and app/
 * import only from here (enforced by ESLint, eslint.config.mjs).
 */
export { DeclarationReview } from './components/DeclarationReview';
export { FlaggedTdsCases } from './components/FlaggedTdsCases';
export { Form16Register } from './components/Form16Register';
export { MyForm16 } from './components/MyForm16';
export { MyTaxDeclarations } from './components/MyTaxDeclarations';
export { OutputDefinitions } from './components/OutputDefinitions';
export { PtStateMaster } from './components/PtStateMaster';
export { SavingsDeclarations } from './components/SavingsDeclarations';
export { SectionMaster } from './components/SectionMaster';
export { StatutoryReturns } from './components/StatutoryReturns';
export { TaxComputation } from './components/TaxComputation';
export { ComputationScreen } from './components/ComputationScreen';
export { DeclarationsScreen } from './components/DeclarationsScreen';
export { Form16Screen } from './components/Form16Screen';
export { OutputDefinitionsScreen } from './components/OutputDefinitionsScreen';
export { ReturnsScreen } from './components/ReturnsScreen';
export { SavingsScreen } from './components/SavingsScreen';
