package in.gov.fci.payroll.allowance.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** {@code cafeteria_election_line}: one selected component and its monthly amount (SRS-11-16). */
@Entity
@Table(name = "cafeteria_election_line")
public class CafeteriaElectionLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "election_line_id")
    private Long id;

    @Column(name = "election_id", nullable = false)
    private Long electionId;

    @Column(name = "component_id", nullable = false)
    private Long componentId;

    @Column(name = "elected_amount", nullable = false)
    private BigDecimal amount;

    protected CafeteriaElectionLine() {}

    public CafeteriaElectionLine(long electionId, long componentId, BigDecimal amount) {
        this.electionId = electionId;
        this.componentId = componentId;
        this.amount = amount;
    }

    public Long getElectionId() {
        return electionId;
    }

    public Long getComponentId() {
        return componentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
