package com.casting.shop.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 返修完成登记。 */
@Data
public class ReworkCompleteRequest {

    private String responsiblePerson;
    private String dispositionMethod;
    private String dispositionNote;
    private String completedBy;
    private LocalDateTime completedAt;
}
