package in.gov.fci.payroll.configuration.api;

import in.gov.fci.payroll.configuration.business.model.LabelMaster;
import in.gov.fci.payroll.configuration.orchestration.LabelService;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-G-39 / SRS-G-40 (NEW-G1-05, NEW-G1-06): the M-26 names of master values for screens. A
 * master-value label names its master ({@code label_master.master_entity}, e.g. {@link #OPERATION}
 * for an M-27 operation code) and the value's code. {@link #shown} gives the label a user sees, in
 * the SRS-G-39 order (the user's language at the office, at the region, Global; then English
 * Global), so a screen can show the local and the standard name together ("Mukadam (Sardar)").
 * {@link #matching} lets a search "find a record by any of its labels". Codes, never labels, stay
 * in calculations and stored data.
 */
@Service
public class MasterLabels {

    /** {@code label_master.master_entity} of an M-27 operation (master code = operation code). */
    public static final String OPERATION = "OPERATION";

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final ConfigurationRepositories.Labels labels;
    private final OrgDirectory org;
    private final Clock clock;

    public MasterLabels(ConfigurationRepositories.Labels labels, OrgDirectory org, Clock clock) {
        this.labels = labels;
        this.org = org;
        this.clock = clock;
    }

    /**
     * The label shown for each value of {@code entity} that has an Active label in force today, by
     * code, for a user at {@code office} reading in {@code language} (EN, HI; null = EN).
     */
    @Transactional(readOnly = true)
    public Map<String, String> shown(String entity, String language, Long office) {
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        Long region =
                office == null
                        ? null
                        : org.ancestorOrSelf(office, LocationType.RO)
                                .map(OrgLocation::getId)
                                .orElse(null);
        String lang = language == null ? "EN" : language.toUpperCase(Locale.ROOT);
        Map<String, List<LabelMaster>> byCode = new LinkedHashMap<>();
        for (LabelMaster label : labels.findByMasterEntity(entity)) {
            byCode.computeIfAbsent(label.getMasterCode(), k -> new ArrayList<>()).add(label);
        }
        Map<String, String> out = new LinkedHashMap<>();
        byCode.forEach(
                (code, versions) ->
                        LabelService.resolve(versions, lang, office, region, today)
                                .ifPresent(l -> out.put(code, l.getLabelText())));
        return out;
    }

    /**
     * SRS-G-39 "Let search find a record by any of its labels": the codes of {@code entity} with an
     * Active label (any language, any scope) whose text contains {@code text}, ignoring case.
     */
    @Transactional(readOnly = true)
    public Set<String> matching(String entity, String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String needle = text.strip().toLowerCase(Locale.ROOT);
        for (LabelMaster label : labels.findByMasterEntity(entity)) {
            if (label.status() == LabelMaster.Status.ACTIVE
                    && label.getLabelText().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(label.getMasterCode());
            }
        }
        return out;
    }
}
