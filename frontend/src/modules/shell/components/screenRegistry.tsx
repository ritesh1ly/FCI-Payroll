'use client';

import {
  AlertTriangle,
  ArrowLeftRight,
  BadgePercent,
  BanknoteArrowUp,
  BookOpenCheck,
  Boxes,
  Calculator,
  CalendarClock,
  CalendarDays,
  CalendarMinus,
  CalendarRange,
  ChartColumn,
  ClipboardCheck,
  ClipboardList,
  CloudUpload,
  Coffee,
  Coins,
  FileBadge,
  FileCog,
  FileDiff,
  FileStack,
  FileText,
  FlaskConical,
  Gauge,
  GitBranch,
  HandCoins,
  Handshake,
  HardHat,
  History,
  Inbox,
  KeyRound,
  Landmark,
  Languages,
  Layers,
  LayoutDashboard,
  Link2,
  ListChecks,
  ListMinus,
  ListTree,
  MessageSquareText,
  Mountain,
  Network,
  OctagonPause,
  PackageOpen,
  Percent,
  PiggyBank,
  Receipt,
  ReceiptIndianRupee,
  Scale,
  ScrollText,
  Send,
  Settings2,
  ShieldCheck,
  SlidersHorizontal,
  UserCheck,
  UserCog,
  UserRound,
  Users,
  Vault,
  Wallet,
  Workflow,
  type LucideIcon,
} from 'lucide-react';
import type { ComponentType } from 'react';
import { AccessGroups, Assignments, RolesMatrix, SignInSecurity, UsersAdmin } from '@/modules/iam';
import { AuditTrail } from '@/modules/audit';
import { IntegrationMonitor } from '@/modules/integration';
import {
  ConsolidatedMis,
  EmployeeMaster,
  EsevaRecord,
  MyHrmsUpdate,
  MyPayslips,
  HrmsChangeApproval,
  LifecycleEvents,
  StaffTransfers,
} from '@/modules/employee';
import {
  AttendanceCapture,
  DepartmentalComputation,
  DpsComputation,
  GangStructure,
  IncentiveComputation,
  IncentiveNorms,
  LabourMaster,
  LabourRates,
  NwnpComputation,
  WorkSlips,
} from '@/modules/labour';
import { LoansScreen } from '@/modules/loans';
import { NpsDeductionScreen } from '@/modules/nps';
import {
  CpfReconciliationReports,
  CpfReports,
  InvoiceOutputs,
  LabourReports,
  MisReports,
  PayslipScreen,
  Remittance,
  ReportCatalogue,
} from '@/modules/reporting';
import { EsevaPortal, EsevaSupport } from '@/modules/eseva';
import {
  LeaveEncashmentArrears,
  LeaveEventsScreen,
  LeaveParametersScreen,
  LeaveTypesScreen,
  MyLeaveBalance,
} from '@/modules/leave';
import { FinalSettlement } from '@/modules/settlement';
import { ArrearEvents, WageRevisions } from '@/modules/arrear';
import { IncrementScreen, PayScalesScreen } from '@/modules/increment';
import { ApprovalInbox, MySubmissions, WorkflowMaster } from '@/modules/workflow';
import {
  ComputationExceptions,
  ExceptionsWorklist,
  HrmsFallback,
  OffCycle,
  PayrollQueries,
  Supplementary,
} from '@/modules/general';
import {
  BankAdvice,
  BatchJobs,
  CycleControl,
  CycleStatusHome,
  DirectPayroll,
  PayrollCalendar,
  PayrollCycle,
  SimulationRun,
} from '@/modules/jobs';
import {
  CpfPendingTile,
  CpfSanctionCard,
  CpfSanctions,
  CpfWithdrawals,
  DeputationAdjustment,
  InterestRates,
  InterestRun,
  MyCpfStatement,
  MyVpf,
  OpeningBalanceCorrection,
  ParentZoneChange,
  StatutoryRates,
  SupplementaryPayment,
  VpfRequests,
} from '@/modules/cpf';
import { CafeteriaScreen, ClaimsScreen } from '@/modules/allowance';
import { PenaltyScreen } from '@/modules/penalty';
import {
  ComputationScreen,
  DeclarationsScreen,
  Form16Screen,
  MyForm16,
  MyTaxDeclarations,
  OutputDefinitionsScreen,
  ReturnsScreen,
  SavingsScreen,
} from '@/modules/tax';
import {
  CeilingNormsScreen,
  ComponentCatalogue,
  Circulars,
  GlMapping,
  Labels,
  PenaltyRulesScreen,
  Operations,
  OtherParameters,
  ReferenceLists,
  RuleCatalogue,
  SpecialRates,
  StaffRates,
  SystemParameters,
} from '@/modules/configuration';

/**
 * Every workspace screen: the live design-pack screens (wireframe body from the live site, keyed by
 * its id there) and the FCILekha screens built on the API. M-01 is both: the live layout with real,
 * scoped data.
 */
export interface ScreenDefinition {
  code: string;
  icon: LucideIcon;
  /** The screen shows a design-pack body, served by /payroll/wireframe/<code> after the VIEW check. */
  wireframe?: boolean;
  component?: ComponentType;
}

/**
 * I-04: one screen for CPF / PF sanctions and Loans & Advances (SRS I-04 "One screen serves both";
 * FRS V0.5 §13, §16), as Wireframes v6.1 draws it under both menu groups. The loans module lays it
 * out; the CPF parts come from their module (the two modules do not import each other).
 */
function LoansAndCpfScreen() {
  return (
    <LoansScreen
      cpfTile={<CpfPendingTile />}
      cpf={(onOpen) => <CpfSanctionCard onOpen={onOpen} />}
      cpfRegister={(opening) => <CpfSanctions opening={opening} />}
    />
  );
}

/**
 * E-01 as Wireframes v6.1 draws it (EsevaPortal, OD-121, OD-135). The functions of the other modules stay
 * whole behind closed sections: the member's own record (with the Aadhaar masked, SRS-17-10), the own
 * CPF withdrawal requests (SRS-6-4 step 1), the VPF form (BR-ESEVA-006), the CPF statement (SRS-17-17),
 * the income-tax declarations and the released Form 16 certificates (SRS-7-9, SRS-7-24, SRS-17-2), the
 * leave balance (SRS-LV-32); the drawn HRMS Update Request card (SRS-1-70, SRS-17-14).
 */
function EsevaScreen() {
  return (
    <EsevaPortal
      record={<EsevaRecord claims={false} />}
      withdrawals={<CpfWithdrawals />}
      vpf={<MyVpf />}
      statement={<MyCpfStatement />}
      tax={<MyTaxDeclarations />}
      form16={<MyForm16 />}
      leave={<MyLeaveBalance />}
      payslips={(onOpen) => <MyPayslips onOpen={onOpen} />}
      hrms={(preview) => <MyHrmsUpdate preview={preview} />}
    />
  );
}

/**
 * E-02: the Self-Service Submission Log and the support preview (plan 14, D14-1) and the members'
 * VPF change requests the Payroll Maker verifies (BR-ESEVA-006).
 */
function EsevaSupportScreen() {
  return (
    <>
      <EsevaSupport />
      <div className="wireframe-body-container">
        <VpfRequests />
      </div>
    </>
  );
}

export const SCREENS: Record<string, ScreenDefinition> = {
  'E-01': { code: 'E-01', icon: UserRound, component: EsevaScreen },
  'E-02': { code: 'E-02', icon: Handshake, component: EsevaSupportScreen },
  'W-01': { code: 'W-01', icon: Inbox, component: ApprovalInbox },
  'W-02': { code: 'W-02', icon: Send, component: MySubmissions },
  'M-01': { code: 'M-01', icon: Users, component: EmployeeMaster },
  'M-02': { code: 'M-02', icon: HardHat, component: LabourMaster },
  'I-01': { code: 'I-01', icon: ClipboardCheck, component: AttendanceCapture },
  'I-02': { code: 'I-02', icon: ClipboardList, component: WorkSlips },
  'M-04': { code: 'M-04', icon: Percent, component: StaffRates },
  'M-05': { code: 'M-05', icon: Coins, component: LabourRates },
  'M-06': { code: 'M-06', icon: BadgePercent, component: IncentiveNorms },
  'M-09': { code: 'M-09', icon: Boxes, component: GangStructure },
  'M-22': { code: 'M-22', icon: Mountain, component: SpecialRates },
  'M-23': { code: 'M-23', icon: SlidersHorizontal, component: OtherParameters },
  'M-24': { code: 'M-24', icon: Landmark, component: GlMapping },
  'M-16': { code: 'M-16', icon: ListChecks, component: ReferenceLists },
  'M-07': { code: 'M-07', icon: Layers, component: ComponentCatalogue },
  'M-08': { code: 'M-08', icon: BookOpenCheck, component: RuleCatalogue },
  'M-10': { code: 'M-10', icon: CalendarDays, component: PayrollCalendar },
  'M-14': { code: 'M-14', icon: Scale, component: PenaltyRulesScreen },
  'M-15': { code: 'M-15', icon: Gauge, component: CeilingNormsScreen },
  'M-25': { code: 'M-25', icon: FileText, component: Circulars },
  'M-26': { code: 'M-26', icon: Languages, component: Labels },
  'M-27': { code: 'M-27', icon: PackageOpen, component: Operations },
  'M-18': { code: 'M-18', icon: Percent, component: StatutoryRates },
  'M-19': { code: 'M-19', icon: PiggyBank, component: InterestRates },
  'I-04': { code: 'I-04', icon: Coins, component: LoansAndCpfScreen },
  'I-07': { code: 'I-07', icon: ReceiptIndianRupee, component: DeclarationsScreen },
  'I-18': { code: 'I-18', icon: Wallet, component: SavingsScreen },
  'C-07': { code: 'C-07', icon: Calculator, component: ComputationScreen },
  'M-17': { code: 'M-17', icon: FileCog, component: OutputDefinitionsScreen },
  'C-10': { code: 'C-10', icon: FileBadge, component: Form16Screen },
  'C-11': { code: 'C-11', icon: FileStack, component: ReturnsScreen },
  'I-03': { code: 'I-03', icon: Receipt, component: ClaimsScreen },
  'I-16': { code: 'I-16', icon: Coffee, component: CafeteriaScreen },
  'I-06': { code: 'I-06', icon: HandCoins, component: PenaltyScreen },
  'I-09': { code: 'I-09', icon: ArrowLeftRight, component: DeputationAdjustment },
  'I-22': { code: 'I-22', icon: FileDiff, component: OpeningBalanceCorrection },
  'I-12': { code: 'I-12', icon: BanknoteArrowUp, component: SupplementaryPayment },
  'I-13': { code: 'I-13', icon: ArrowLeftRight, component: ParentZoneChange },
  'C-12': { code: 'C-12', icon: BadgePercent, component: InterestRun },
  'I-17': { code: 'I-17', icon: CalendarClock, component: LifecycleEvents },
  'M-11': { code: 'M-11', icon: ArrowLeftRight, component: StaffTransfers },
  'D-01': { code: 'D-01', icon: LayoutDashboard, component: CycleStatusHome },
  'C-01': { code: 'C-01', icon: ShieldCheck, component: CycleControl },
  'C-02': { code: 'C-02', icon: Calculator, component: PayrollCycle },
  'C-03': { code: 'C-03', icon: HardHat, component: DepartmentalComputation },
  'C-04': { code: 'C-04', icon: HardHat, component: DpsComputation },
  'C-05': { code: 'C-05', icon: HardHat, component: NwnpComputation },
  'C-06': { code: 'C-06', icon: BadgePercent, component: IncentiveComputation },
  'C-08': { code: 'C-08', icon: AlertTriangle, component: ComputationExceptions },
  'C-09': { code: 'C-09', icon: FlaskConical, component: SimulationRun },
  'C-13': { code: 'C-13', icon: Workflow, component: BatchJobs },
  'X-03': { code: 'X-03', icon: Link2, component: IntegrationMonitor },
  'X-05': { code: 'X-05', icon: AlertTriangle, component: ExceptionsWorklist },
  'X-06': { code: 'X-06', icon: MessageSquareText, component: PayrollQueries },
  'X-07': { code: 'X-07', icon: CloudUpload, component: HrmsFallback },
  'X-08': { code: 'X-08', icon: KeyRound, component: SignInSecurity },
  'I-19': { code: 'I-19', icon: ListMinus, component: DirectPayroll },
  'I-14': { code: 'I-14', icon: Vault, component: NpsDeductionScreen },
  'M-13': { code: 'M-13', icon: ListTree, component: LeaveTypesScreen },
  'M-20': { code: 'M-20', icon: CalendarRange, component: LeaveParametersScreen },
  'I-11': { code: 'I-11', icon: CalendarMinus, component: LeaveEventsScreen },
  'O-05': { code: 'O-05', icon: Handshake, component: FinalSettlement },
  'I-08': { code: 'I-08', icon: History, component: ArrearEvents },
  'I-24': { code: 'I-24', icon: History, component: LeaveEncashmentArrears },
  'I-25': { code: 'I-25', icon: History, component: WageRevisions },
  'I-05': { code: 'I-05', icon: History, component: IncrementScreen },
  'M-03': { code: 'M-03', icon: History, component: PayScalesScreen },
  'I-21': { code: 'I-21', icon: UserCheck, component: HrmsChangeApproval },
  'I-20': { code: 'I-20', icon: HandCoins, component: OffCycle },
  'I-23': { code: 'I-23', icon: BanknoteArrowUp, component: Supplementary },
  'O-01': { code: 'O-01', icon: Receipt, component: PayslipScreen },
  'O-02': { code: 'O-02', icon: OctagonPause, component: BankAdvice },
  'O-11': { code: 'O-11', icon: Send, component: Remittance },
  'R-01': { code: 'R-01', icon: ChartColumn, component: ConsolidatedMis },
  'O-03': { code: 'O-03', icon: BookOpenCheck, component: LabourReports },
  'O-04': { code: 'O-04', icon: FileStack, component: MisReports },
  'O-06': { code: 'O-06', icon: ReceiptIndianRupee, component: InvoiceOutputs },
  'O-07': { code: 'O-07', icon: FileStack, component: CpfReports },
  'O-08': { code: 'O-08', icon: FileStack, component: CpfReconciliationReports },
  'M-12': { code: 'M-12', icon: FileCog, component: ReportCatalogue },
  'A-01': { code: 'A-01', icon: UserCog, component: UsersAdmin },
  'A-02': { code: 'A-02', icon: KeyRound, component: RolesMatrix },
  'A-03': { code: 'A-03', icon: Network, component: AccessGroups },
  'A-04': { code: 'A-04', icon: Link2, component: Assignments },
  'A-05': { code: 'A-05', icon: ScrollText, component: AuditTrail },
  'A-07': { code: 'A-07', icon: Settings2, component: SystemParameters },
  'A-08': { code: 'A-08', icon: GitBranch, component: WorkflowMaster },
};
