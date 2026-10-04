package com.casting.shop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 返修结果的追加更正说明；复检单创建后只能追加，不能覆盖原始处置记录。 */
@Entity
@Table(name = "rework_correction")
@Getter
@Setter
public class ReworkCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rework_round_id", nullable = false)
    private Long reworkRoundId;

    @Column(name = "correction_note", nullable = false, length = 255)
    private String correctionNote;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
