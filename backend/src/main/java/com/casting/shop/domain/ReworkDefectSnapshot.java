package com.casting.shop.domain;

import com.casting.shop.common.Dict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 每一轮返修所依据的上一轮缺陷快照，复制后不可修改。 */
@Entity
@Table(name = "rework_defect_snapshot")
@Getter
@Setter
public class ReworkDefectSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rework_round_id", nullable = false)
    private Long reworkRoundId;

    /** 第 1 轮引用首次检验缺陷；后续轮引用上一轮快照 */
    @Column(name = "source_defect_id")
    private Long sourceDefectId;

    @Column(name = "source_snapshot_id")
    private Long sourceSnapshotId;

    @Column(name = "defect_type", nullable = false, length = 16)
    private String defectType;

    @Column(name = "severity", length = 16)
    private String severity;

    @Column(name = "defect_position", length = 32)
    private String position;

    @Column(name = "size_mm", precision = 6, scale = 2)
    private BigDecimal sizeMm;

    private Integer quantity;

    @Column(name = "remark", length = 64)
    private String remark;

    @Transient
    public String getDefectTypeName() {
        return Dict.defectTypeName(defectType);
    }

    @Transient
    public String getSeverityName() {
        return Dict.severityName(severity);
    }
}
