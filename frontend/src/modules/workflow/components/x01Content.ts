/** The field-source badges drawn on X-01. */
type Source = 'SYS' | 'ENT' | 'RULE';

/**
 * The reference content of X-01 Approval Workflow Master & RBAC exactly as Wireframes v6.1 draws it (OD-121, screen
 * design of the SRS, section "In the screen design, X-01 holds", SRS-22-12): which tables and panels stand on the
 * screen, their columns with source badges and their rows. The rows of these tables are the pack's governance text
 * (message keys, en + hi), read-only: what is enforced is the Approval Workflow Master (the configured routes,
 * approval_workflow_action / _level) and the role grants (app_role_permission), both listed live on the same screen
 * (WIREFRAME_GAPS WF-192). Generated once from the pack; keep it in step with the frozen pack, not with the build.
 */
export type Tone = 'info' | 'pur' | 'bad' | 'warn' | 'gray';

/** A text: a message key, a dash, or a cell with its parts as drawn. */
export type Cell =
  | string
  | {
      /** Message key of the cell. */
      k?: string;
      /** A figure as drawn (no translation). */
      raw?: string;
      strong?: boolean;
      /** Bold text after the cell's text. */
      tail?: string;
      /** The small line under the text. */
      sub?: string;
      pill?: 'ok';
    };

export interface X01Table {
  title: string;
  cols: { t: string; s: Source; num?: boolean }[];
  rows?: Cell[][];
  note?: { lead?: string; text: string };
}

export interface X01Panel {
  title: string;
  items: { icon: string; tone: Tone; src: Source; title: string; text: string }[];
  note?: string;
}

export const X01_TABLES = {
  screenMatrix: {
    title: 'x01.x.screenLevelAccessMatrixFRS6',
    cols: [
      {
        t: 'x01.x.screen',
        s: 'SYS',
      },
      {
        t: 'x01.x.maker',
        s: 'ENT',
      },
      {
        t: 'x01.x.checker',
        s: 'ENT',
      },
      {
        t: 'x01.x.labourDH',
        s: 'ENT',
      },
      {
        t: 'x01.x.hR',
        s: 'ENT',
      },
      {
        t: 'x01.x.finance',
        s: 'ENT',
      },
      {
        t: 'x01.x.admin',
        s: 'ENT',
      },
      {
        t: 'x01.x.member',
        s: 'ENT',
      },
    ],
  },
  actionMatrix: {
    title: 'x01.x.actionLevelMakerCheckerMatrix',
    cols: [
      {
        t: 'x01.x.action',
        s: 'SYS',
      },
      {
        t: 'x01.x.maker',
        s: 'ENT',
      },
      {
        t: 'x01.x.checker',
        s: 'ENT',
      },
      {
        t: 'x01.x.labourDH',
        s: 'ENT',
      },
      {
        t: 'x01.x.hR',
        s: 'ENT',
      },
      {
        t: 'x01.x.admin',
        s: 'ENT',
      },
      {
        t: 'x01.x.member',
        s: 'ENT',
      },
    ],
    rows: [
      ['x01.x.initiateFreezePeriod', 'x01.x.create', 'x01.x.verifyOTP', '—', '—', 'x01.x.configure', '—'],
      ['x01.x.runComputationAnyEngine', 'x01.x.execute', 'x01.x.view', '—', '—', '—', '—'],
      [
        'x01.x.captureLabourAttendanceWorkSlip',
        'x01.x.view',
        'x01.x.verify',
        {
          k: 'x01.x.create',
          strong: true,
        },
        'x01.x.view',
        '—',
        '—',
      ],
      ['x01.x.verifyMonthEndLock', '—', 'x01.x.authoriseOTP', '—', '—', '—', '—'],
      [
        {
          k: 'x01.x.remittanceDisbursementReleaseEBSAPPosting',
          sub: 'x01.x.financeReleases664',
        },
        '—',
        'x01.x.approveOTP',
        '—',
        '—',
        '—',
        '—',
      ],
      ['x01.x.controlledUndo', 'x01.x.request', 'x01.x.verify', '—', '—', 'x01.x.authoriseOTP', '—'],
      ['x01.x.masterAttributeChange', 'x01.x.view', 'x01.x.approve', '—', 'x01.x.create', '—', '—'],
      [
        'x01.x.ruleRateNormVersion',
        'x01.x.view',
        'x01.x.approve',
        '—',
        '—',
        'x01.x.createRuleAdministrator',
        '—',
      ],
      [
        {
          k: 'x01.x.cPFSanctionAdvancePartFinalFinal',
          sub: 'x01.x.unitCPFAdministratorVerifiesZOHQ',
        },
        '—',
        '—',
        '—',
        '—',
        '—',
        'x01.x.requestESevaSSP',
      ],
      [
        'x01.x.loanSanctionForeclosureLabourEmployeeVia',
        'x01.x.setUp',
        'x01.x.approveOTPOnForeclosure',
        '—',
        '—',
        '—',
        'x01.x.request',
      ],
      [
        'x01.x.hRMSChangeWithPayEffectI',
        'x01.x.orderDetailsCopy',
        'x01.x.approveRejectOTPForIncrementDate',
        '—',
        '—',
        '—',
        '—',
      ],
      ['x01.x.hRMSFallbackUploadX07', 'x01.x.upload', 'x01.x.approveOTP', '—', '—', 'x01.x.enableHQ', '—'],
      ['x01.x.retroEventMerge', 'x01.x.capture', 'x01.x.approve', '—', '—', 'x01.x.configureWindow', '—'],
      ['x01.x.overAndAbovePayment', 'x01.x.capture', 'x01.x.approve', '—', '—', '—', '—'],
      ['x01.x.fFSettlement', 'x01.x.compute', 'x01.x.approveOTP', '—', 'x01.x.view', '—', '—'],
      ['x01.x.roleOrAccessGroupChange', '—', 'x01.x.approveOTP', '—', '—', 'x01.x.create', '—'],
      ['x01.x.taxDeclaration', 'x01.x.validate', 'x01.x.approve', '—', '—', '—', 'x01.x.submitOwn'],
    ],
  },
  orgScope: {
    title: 'x01.x.organisationalScopeFCISFullOffice',
    cols: [
      {
        t: 'x01.x.level',
        s: 'ENT',
      },
      {
        t: 'x01.x.count',
        s: 'ENT',
        num: true,
      },
      {
        t: 'x01.x.confirmedAsAnAccessGroupApproval',
        s: 'RULE',
      },
    ],
    rows: [
      [
        'x01.x.headquarters',
        {
          raw: '1',
        },
        {
          k: 'x01.x.confirmed',
          pill: 'ok',
        },
      ],
      [
        'x01.x.zone',
        {
          raw: '5',
        },
        {
          k: 'x01.x.confirmedZOCycleAndCPFTier',
          pill: 'ok',
        },
      ],
      [
        'x01.x.region',
        {
          raw: '26',
        },
        {
          k: 'x01.x.confirmed',
          pill: 'ok',
        },
      ],
      [
        'x01.x.division',
        {
          raw: '166',
        },
        {
          k: 'x01.x.confirmedDOCycleWhereMakerChecker',
          pill: 'ok',
        },
      ],
      [
        'x01.x.depotUnit',
        'x01.x.underEachDivisionOwnedHired',
        {
          k: 'x01.x.confirmedNoApprovalAuthorityApprovalAt',
          pill: 'ok',
          sub: 'x01.x.labourDealingHandScopingToDepot',
        },
      ],
    ],
    note: {
      lead: 'x01.x.confirmedInFRSV046',
      text: 'x01.x.everyLevelIsNowAnApproval',
    },
  },
  designations: {
    title: 'x01.x.fCIDesignationsFRSRolesFRSV0',
    cols: [
      {
        t: 'x01.x.fCIDesignation',
        s: 'SYS',
      },
      {
        t: 'x01.x.roleInThisPack',
        s: 'SYS',
      },
    ],
    rows: [
      ['x01.x.assistantAccountsPayrollClerkDepotAssistant', 'x01.x.payrollMaker'],
      ['x01.x.managerAccountsDDO', 'x01.x.payrollChecker'],
      ['x01.x.aGMAccountsCPF', 'x01.x.finalApproverWhereAThirdTier'],
      ['x01.x.labourSupervisorDepotManager', 'x01.x.labourDealingHand'],
      ['x01.x.systemAdministratorSuperAdmin', 'x01.x.administrator'],
      ['x01.x.unitSuperUser', 'x01.x.administratorScopedToOneUnitDelegated'],
      ['x01.x.billSection', 'x01.x.financeGovernanceReports'],
      ['x01.x.vigilanceSection', 'x01.x.readOnlyAuditAccessGroupConfigured'],
    ],
  },
  delegation: {
    title: 'x01.x.delegationOfAuthorityHQZORO',
    cols: [
      {
        t: 'x01.x.level',
        s: 'SYS',
      },
      {
        t: 'x01.x.ownCycleInstance',
        s: 'SYS',
      },
      {
        t: 'x01.x.approvalAuthority',
        s: 'RULE',
      },
      {
        t: 'x01.x.status',
        s: 'SYS',
      },
    ],
    rows: [
      [
        'x01.x.hQ',
        'x01.x.yesHQStaff',
        'x01.x.ruleVersionsAllIndiaDAHRA',
        {
          k: 'x01.x.confirmed',
          pill: 'ok',
        },
      ],
      [
        'x01.x.zonalOffice',
        'x01.x.yesZOStaff',
        'x01.x.aGMCPFCPFTier2Deputation',
        {
          k: 'x01.x.confirmed',
          pill: 'ok',
        },
      ],
      [
        'x01.x.regionalOffice',
        'x01.x.yes',
        'x01.x.cycleLockMasterHRMSChangeTasks',
        {
          k: 'x01.x.confirmed',
          pill: 'ok',
        },
      ],
      [
        'x01.x.divisionalOffice',
        'x01.x.yesIfDOStaffsMakerChecker',
        {
          k: 'x01.x.dOCycleLockLabourAttendanceWork',
          tail: 'x01.x.labourPayGeneratedAtDOOnly',
        },
        {
          k: 'x01.x.confirmed',
          pill: 'ok',
        },
      ],
      [
        'x01.x.depotUnit',
        'x01.x.no',
        'x01.x.noneApprovedAtParentDORO',
        {
          k: 'x01.x.confirmed',
          pill: 'ok',
        },
      ],
    ],
  },
  transactionMatrix: {
    title: 'x01.x.makerCheckerApproverMatrixFRSV0',
    cols: [
      {
        t: 'x01.x.transactionClass',
        s: 'SYS',
      },
      {
        t: 'x01.x.maker',
        s: 'RULE',
      },
      {
        t: 'x01.x.checker',
        s: 'RULE',
      },
      {
        t: 'x01.x.finalApproval',
        s: 'RULE',
      },
      {
        t: 'x01.x.level',
        s: 'RULE',
      },
      {
        t: 'x01.x.oTP',
        s: 'RULE',
      },
    ],
    rows: [
      [
        'x01.x.employeeMasterChangeM01',
        'x01.x.establishmentHR',
        'x01.x.payrollChecker',
        'x01.x.checkerFinal',
        'x01.x.rODOPayingUnitHQFor',
        'x01.x.no',
      ],
      [
        'x01.x.nameChangeFromHRMSI21',
        'x01.x.hRMSInterface',
        '—',
        'x01.x.noPayEffectAppliedAutomatically',
        '—',
        'x01.x.no',
      ],
      [
        'x01.x.dateOfBirthChangeFromHRMS',
        'x01.x.payrollMakerTaskOpenedAutomaticallyDOB',
        'x01.x.payrollChecker',
        'x01.x.checkerHQEstablishmentAlsoWhereThe',
        'x01.x.payingUnitHQPastRetirementDate',
        'x01.x.no',
      ],
      [
        'x01.x.otherHRMSChangeWithPayEffect',
        'x01.x.payrollMakerTaskOpenedAutomaticallyValue',
        'x01.x.payrollChecker',
        'x01.x.checkerFinal',
        'x01.x.payingUnitHQForHQStaff',
        'x01.x.yesForIncrementDateOrBasic',
      ],
      [
        'x01.x.hRMSFallbackFileUploadX07',
        'x01.x.payrollMaker',
        'x01.x.payrollChecker',
        'x01.x.checkerFinalAfterHQEnablesThe',
        'x01.x.payingUnitHQEnables',
        'x01.x.yes',
      ],
      [
        'x01.x.labourAttendanceAndWorkSlipI',
        'x01.x.labourDealingHandDPSBookingSlips',
        'x01.x.payrollChecker',
        'x01.x.checkerFinal',
        'x01.x.dODepotsUnderIt',
        'x01.x.no',
      ],
      [
        'x01.x.allowanceReimbursementClaimI03',
        'x01.x.payrollMakerLabourClaimsEmployeeClaims',
        'x01.x.payrollChecker',
        'x01.x.checkerFinal',
        'x01.x.dORO',
        'x01.x.no',
      ],
      [
        'x01.x.loanAdvanceSanctionAndRecoveryI',
        'x01.x.payrollMaker',
        'x01.x.payrollChecker',
        'x01.x.checkerAgainstTheSanctionOrder',
        'x01.x.rO',
        'x01.x.yesForForeclosure',
      ],
      [
        'x01.x.incrementAndPayFixationI05',
        'x01.x.payrollMakerHRMSChangeTask',
        'x01.x.payrollChecker',
        'x01.x.checkerFinal',
        'x01.x.rOHQForHQStaff',
        'x01.x.yesForIncrementDateOrBasic',
      ],
      [
        'x01.x.penaltyRecoveryOrderI06',
        'x01.x.payrollMaker',
        'x01.x.payrollChecker',
        'x01.x.checkerAgainstTheOrder',
        'x01.x.rO',
        'x01.x.no',
      ],
      [
        'x01.x.arrearEventI08',
        'x01.x.payrollMaker',
        'x01.x.payrollChecker',
        'x01.x.checkerAllIndiaRateEventsDefined',
        'x01.x.hQDefinesRatePayingUnitRuns',
        'x01.x.yesForHQWageRevisionApproval',
      ],
      [
        'x01.x.cPFAdjustmentDeputationI09',
        'x01.x.managerCPF',
        'x01.x.aGMCPF',
        'x01.x.aGMCPF',
        'x01.x.zOHQ',
        'x01.x.no',
      ],
      [
        'x01.x.cPFAdvancePartFinalWithdrawal',
        'x01.x.memberESevaOrCPFSSPUnit',
        'x01.x.zonalHQCPFDivision',
        'x01.x.cPFSanctioningRoleAtZOHQ',
        'x01.x.unitZOHQ',
        'x01.x.yes',
      ],
      [
        'x01.x.cPFFinalSettlement',
        'x01.x.unitCPFAdministrator',
        'x01.x.zonalHQCPFDivision',
        'x01.x.cPFSanctioningRoleAtZOHQ',
        'x01.x.zOHQ',
        'x01.x.yes',
      ],
      [
        'x01.x.pFCPFOpeningBalanceCorrectionI',
        'x01.x.managerCPF',
        'x01.x.aGMCPF',
        'x01.x.hQCPFApproverEveryCorrection',
        'x01.x.zOHQ',
        'x01.x.yes',
      ],
      [
        'x01.x.fullFinalSettlementO05',
        'x01.x.caseOpensOnSeparationPayrollMaker',
        'x01.x.payrollChecker',
        'x01.x.checkerNetRecoveryConfiguredLevelHQ',
        'x01.x.payingUnit',
        'x01.x.yes',
      ],
      [
        'x01.x.payrollCycleFreezeAndMonthEnd',
        'x01.x.payrollMaker',
        'x01.x.payrollChecker',
        'x01.x.checkerLock',
        'x01.x.ownCycleInstance',
        'x01.x.yes',
      ],
      [
        'x01.x.undoTransactionBeforeEBSPosting',
        'x01.x.payrollMakerRequest',
        'x01.x.payrollCheckerVerify',
        'x01.x.administratorAuthorisation',
        'x01.x.ownCycleInstance',
        'x01.x.yes',
      ],
      [
        'x01.x.supplementaryPaymentAfterLockI23',
        'x01.x.payrollMaker',
        'x01.x.payrollChecker',
        'x01.x.checkerOrFurtherLevelWhereConfigured',
        'x01.x.payingUnit',
        'x01.x.yes',
      ],
      [
        'x01.x.maintainDirectPayrollPTLWFCS',
        'x01.x.payrollMaker',
        'x01.x.payrollChecker',
        'x01.x.checker',
        'x01.x.payingUnit',
        'x01.x.no',
      ],
      [
        'x01.x.cycleUnFreezeReleaseOfA',
        'x01.x.payrollMaker',
        'x01.x.payrollChecker',
        'x01.x.authorityConfiguredInTheApprovalWorkflow',
        'x01.x.ownCycleInstance',
        'x01.x.yes',
      ],
      [
        'x01.x.remittanceAndDisbursementRelease',
        'x01.x.finance',
        'x01.x.payrollChecker',
        'x01.x.checkerFinal',
        'x01.x.payingUnit',
        'x01.x.yes',
      ],
      [
        'x01.x.ruleParameterVersionM07M',
        'x01.x.ruleAdministrator',
        'x01.x.ruleApprover',
        'x01.x.ruleApprover',
        'x01.x.hQOnly',
        'x01.x.no',
      ],
      [
        'x01.x.leavePaySettingsDivisorAndEncashment',
        'x01.x.administrator',
        'x01.x.checker',
        'x01.x.checkerFinal',
        'x01.x.hQOnly',
        'x01.x.no',
      ],
      [
        'x01.x.roleOrAccessGroupAssignment',
        'x01.x.administrator',
        'x01.x.payrollChecker',
        'x01.x.checkerFinal',
        'x01.x.hQZOForZonalUsers',
        'x01.x.yes',
      ],
    ],
  },
  routes: {
    title: 'x01.x.approvalWorkflowMasterConfiguredApprovalActions',
    cols: [
      {
        t: 'x01.x.approvalActionScreen',
        s: 'RULE',
      },
      {
        t: 'x01.x.officeLevel',
        s: 'RULE',
      },
      {
        t: 'x01.x.levels',
        s: 'RULE',
      },
      {
        t: 'x01.x.rolePerLevel',
        s: 'RULE',
      },
      {
        t: 'x01.x.oTP',
        s: 'RULE',
      },
      {
        t: 'x01.x.attachment',
        s: 'RULE',
      },
      {
        t: 'x01.x.targetTAT',
        s: 'RULE',
      },
      {
        t: 'x01.x.version',
        s: 'SYS',
      },
      {
        t: 'x01.x.actions',
        s: 'SYS',
      },
    ],
  },
  activeUsers: {
    title: 'x01.x.activeUserReport',
    cols: [
      {
        t: 'x01.x.userID',
        s: 'SYS',
      },
      {
        t: 'x01.x.role',
        s: 'SYS',
      },
      {
        t: 'x01.x.unitAccessGroup',
        s: 'SYS',
      },
      {
        t: 'x01.x.lastLogin',
        s: 'SYS',
      },
      {
        t: 'x01.x.flag',
        s: 'SYS',
      },
    ],
  },
} satisfies Record<string, X01Table>;

export const X01_PANELS = {
  enforcement: {
    title: 'x01.x.enforcementPrinciples',
    items: [
      {
        icon: '🔐',
        tone: 'pur',
        src: 'RULE',
        title: 'x01.x.segregationOfDuties',
        text: 'x01.x.aMakerCanNeverCheckTheir',
      },
      {
        icon: '👁',
        tone: 'info',
        src: 'RULE',
        title: 'x01.x.memberSelfScope',
        text: 'x01.x.eSevaUserReachesExactlyOneScreen',
      },
      {
        icon: '🚫',
        tone: 'bad',
        src: 'SYS',
        title: 'x01.x.menuLevelNotJustButtonLevel',
        text: 'x01.x.unauthorisedScreensAreNotRenderedAt',
      },
      {
        icon: '🏭',
        tone: 'warn',
        src: 'RULE',
        title: 'x01.x.depotGangScoping',
        text: 'x01.x.labourDealingHandsScopedServerSide',
      },
      {
        icon: '🔑',
        tone: 'gray',
        src: 'SYS',
        title: 'x01.x.authentication',
        text: 'x01.x.applicationManagedUserIDAndPassword',
      },
    ],
    note: 'x01.x.thisMatrixGeneratesTheMenuA',
  },
  rules: {
    title: 'x01.x.approvalWorkflowMasterRules',
    items: [
      {
        icon: '🧩',
        tone: 'info',
        src: 'RULE',
        title: 'x01.x.approvalAsMaster',
        text: 'x01.x.everyControlledTransactionClassIsAn',
      },
      {
        icon: '🏢',
        tone: 'info',
        src: 'RULE',
        title: 'x01.x.roleOfficeLevel',
        text: 'x01.x.eachLevelIsAssignedToAn',
      },
      {
        icon: '2️⃣',
        tone: 'info',
        src: 'RULE',
        title: 'x01.x.twoOrThreeLevels',
        text: 'x01.x.fewerThanTwoIsRejectedNo',
      },
      {
        icon: '👥',
        tone: 'pur',
        src: 'SYS',
        title: 'x01.x.identitySeparation',
        text: 'x01.x.aUserNeverApprovesAnItem',
      },
      {
        icon: '📅',
        tone: 'info',
        src: 'SYS',
        title: 'x01.x.versioned',
        text: 'x01.x.anItemInProgressCompletesUnder',
      },
      {
        icon: '🏛',
        tone: 'warn',
        src: 'SYS',
        title: 'x01.x.hQMaintenanceNoAutoApprove',
        text: 'x01.x.maintainedAtHQUnderMakerChecker',
      },
      {
        icon: '🏦',
        tone: 'info',
        src: 'RULE',
        title: 'x01.x.cPFAuthority',
        text: 'x01.x.competentAuthorityForCPFTheCPF',
      },
    ],
  },
  authentication: {
    title: 'x01.x.authenticationSessionUserGovernance67',
    items: [
      {
        icon: '🔐',
        tone: 'info',
        src: 'SYS',
        title: 'x01.x.userIDPasswordAndOTP',
        text: 'x01.x.applicationManagedUserIDOfficialE',
      },
      {
        icon: '🔑',
        tone: 'info',
        src: 'RULE',
        title: 'x01.x.passwordPolicyOTPResetUnlock',
        text: 'x01.x.valuesSetAsPerFCIS',
      },
      {
        icon: '1️⃣',
        tone: 'info',
        src: 'SYS',
        title: 'x01.x.singleActiveSession',
        text: 'x01.x.aNewLoginEndsTheExisting',
      },
      {
        icon: '⏱',
        tone: 'info',
        src: 'RULE',
        title: 'x01.x.inactivityLogout',
        text: 'x01.x.configurableIdlePeriodsPayrollUsersAnd',
      },
      {
        icon: '🔀',
        tone: 'warn',
        src: 'SYS',
        title: 'x01.x.revocationOnTransferAndSeparation',
        text: 'x01.x.allRightsRevokedFromTheTransfer',
      },
      {
        icon: '🏢',
        tone: 'info',
        src: 'RULE',
        title: 'x01.x.decentralisedAdministration',
        text: 'x01.x.zORODOAdminsManageUsers',
      },
      {
        icon: '🛰',
        tone: 'gray',
        src: 'SYS',
        title: 'x01.x.loginMonitoring',
        text: 'x01.x.iPAddressDeviceBrowserAndWhere',
      },
    ],
  },
} satisfies Record<string, X01Panel>;
