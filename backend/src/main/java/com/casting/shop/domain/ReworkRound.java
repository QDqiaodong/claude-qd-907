package com.casting.shop.domain;

import com.casting.shop.common.Dict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 返修轮次。
 *
 * <p>PENDING 待返修 → WAIT_REINSPECT 待复检；复检 PASS/SCRAPPED 永久结束；
 * 复检 REWORK 时旧轮置为 REINSPECTED，并新增下一轮待返修。</p>
 */
@Entity
@Table(name = "rework_round")
@Getter
@Setter
public class ReworkRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rework_order_id", nullable = false)
    private Long reworkOrderId;

    @Column(name = "casting_id", nullable = false)
    private Long castingId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** PENDING / WAIT_REINSPECT / PASS / SCRAPPED / REINSPECTED */
    @Column(name = "stage", nullable = false, length = 20)
    private String stage;

    @Column(name = "source_inspection_id", nullable = false)
    private Long sourceInspectionId;

    @Column(name = "responsible_person", length = 32)
    private String responsiblePerson;

    @Column(name = "disposition_method", length = 20)
    private String dispositionMethod;

    @Column(name = "disposition_note", length = 255)
    private String dispositionNote;

    @Column(name = "completed_by", length = 32)
    private String completedBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "withdrawn_by", length = 32)
    private String withdrawnBy;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Column(name = "reinspection_id")
    private Long reinspectionId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Version
    private Integer version;

    @Transient
    public String getStageName() {
        return Dict.reworkStageName(stage);
    }

    @Transient
    public String getStageColor() {
        return Dict.reworkStageColor(stage);
    }

    @Transient
    public String getDispositionMethodName() {
        return Dict.reworkMethodName(dispositionMethod);
    }
}
