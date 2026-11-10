package in.gov.fci.payroll.cpf.business;

import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of a CPF withdrawal purpose and its document checklist (SRS-6-5, SRS-6-14, SRS-6-39;
 * BR-CPF-028, 032, 037, 039; DDL {@code cpf_withdrawal_purpose}, {@code cpf_purpose_document}). A
 * purpose is effective-dated: a change is a new version from a later date, never an edit.
 */
public final class PurposeRules {

    /** {@code withdrawal_category} (DDL CHECK). */
    public enum Category {
        ADVANCE,
        PART_FINAL,
        FINAL
    }

    /** {@code verification_tier} (DDL CHECK): the tier that verifies a document. */
    public enum Tier {
        UNIT_CPF_ADMIN,
        ZONAL_HQ_CPF_DIVISION,
        COMPETENT_AUTHORITY
    }

    /** One document of the checklist. */
    public record Document(String documentType, boolean mandatory, Tier tier) {}

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");

    private PurposeRules() {}

    /**
     * BR-CPF-028: Covid-19 is no longer offered as a purpose; the code is checked as typed, and a
     * code is upper-case letters, digits and underscores.
     */
    public static void requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new FciException(CpfErrorCode.PURPOSE_CODE);
        }
        if (code.toUpperCase(Locale.ROOT).contains("COVID")) {
            throw new FciException(CpfErrorCode.PURPOSE_WITHDRAWN);
        }
    }

    /** The limit key is one whose basis the SRS states, or none (not configured at tier 2). */
    public static void requireLimitKey(String ruleKey, Category category) {
        if (ruleKey == null) {
            return;
        }
        Optional<WithdrawalLimit> limit = WithdrawalLimit.ofRuleKey(ruleKey);
        if (limit.isEmpty() || category == Category.FINAL) {
            throw new FciException(CpfErrorCode.PURPOSE_LIMIT_KEY);
        }
    }

    /** {@code max_occurrences} NUMBER(3): a positive quota or none. */
    public static void requireQuota(Integer maxOccurrences) {
        if (maxOccurrences != null && (maxOccurrences < 1 || maxOccurrences > 999)) {
            throw new FciException(CpfErrorCode.PURPOSE_QUOTA);
        }
    }

    /**
     * SRS-6-14: the checklist names each document type of the master once ({@code
     * uk_cpf_purpose_doc}); every type must exist on the document type master.
     */
    public static void requireDocuments(List<Document> documents, Collection<String> knownTypes) {
        Set<String> seen = new HashSet<>();
        for (Document d : documents == null ? List.<Document>of() : documents) {
            if (d == null || d.documentType() == null || !knownTypes.contains(d.documentType())) {
                throw new FciException(CpfErrorCode.PURPOSE_DOCUMENT);
            }
            if (!seen.add(d.documentType())) {
                throw new FciException(CpfErrorCode.PURPOSE_DOCUMENT);
            }
        }
    }

    /**
     * A new version starts after the one in force ({@code uk_cpf_purpose} and the effective-dated
     * history): the current version then ends the day before.
     */
    public static void requireAfter(LocalDate from, Optional<LocalDate> currentFrom) {
        if (from == null || currentFrom.map(c -> !from.isAfter(c)).orElse(false)) {
            throw new FciException(CpfErrorCode.PURPOSE_DATE);
        }
    }
}
