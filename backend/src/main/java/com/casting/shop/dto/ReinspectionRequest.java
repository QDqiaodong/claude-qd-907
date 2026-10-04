package com.casting.shop.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 基于上一轮缺陷发起复检。 */
@Data
public class ReinspectionRequest {

    private String inspector;
    private BigDecimal hardnessHb;
    private String verdict;
    private String remark;
    private List<DefectRequest> defects = new ArrayList<>();
}
