package com.casting.shop.dto;

import lombok.Data;

/** 已生成复检后的返修结果只能追加更正，不能改写。 */
@Data
public class ReworkCorrectionRequest {

    private String correctionNote;
    private String operator;
}
