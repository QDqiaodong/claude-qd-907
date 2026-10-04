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

/** 返修单：一张首次「返修」检验单对应一条不可删除的返修闭环。 */
@Entity
@Table(name = "rework_order")
@Getter
@Setter
public class ReworkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rework_no", nullable = false, unique = true, length = 24)
    private String reworkNo;

    @Column(name = "casting_id", nullable = false)
    private Long castingId;

    @Column(name = "casting_no", nullable = false, length = 24)
    private String castingNo;

    @Column(name = "initial_inspection_id", nullable = false)
    private Long initialInspectionId;

    @Column(name = "current_round_no", nullable = false)
    private Integer currentRoundNo = 1;

    /** ACTIVE / PASS / SCRAPPED */
    @Column(name = "status", length = 16)
    private String status;

    /** 当前待办阶段：PENDING / WAIT_REINSPECT；终态为 PASS / SCRAPPED */
    @Column(name = "current_stage", length = 20)
    private String currentStage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** 乐观锁兜底；同一铸件并发首检主要依靠 casting 行悲观锁串行化 */
    @Version
    private Integer version;

    @Transient
    public String getStatusName() {
        return switch (status) {
            case "PASS" -> "复检合格";
            case "SCRAPPED" -> "复检报废";
            default -> "返修闭环中";
        };
    }

    @Transient
    public String getStageName() {
        return Dict.reworkStageName(getStage());
    }

    @Transient
    public String getStageColor() {
        return Dict.reworkStageColor(getStage());
    }

    /** 当前积压阶段，详情列表由最新轮次另行计算。 */
    @Transient
    public String getStage() {
        return currentStage;
    }
}
