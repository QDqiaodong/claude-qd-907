package com.casting.shop.controller;

import com.casting.shop.common.ApiResponse;
import com.casting.shop.domain.Inspection;
import com.casting.shop.domain.ReworkCorrection;
import com.casting.shop.domain.ReworkRound;
import com.casting.shop.dto.ReinspectionRequest;
import com.casting.shop.dto.ReworkCompleteRequest;
import com.casting.shop.dto.ReworkCorrectionRequest;
import com.casting.shop.dto.ReworkWithdrawRequest;
import com.casting.shop.service.ReworkService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 返修处置与复检闭环。 */
@RestController
@RequestMapping("/api/rework")
public class ReworkController {

    private final ReworkService service;

    public ReworkController(ReworkService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/order/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping("/round/{id}/complete")
    public ApiResponse<ReworkRound> complete(@PathVariable Long id, @RequestBody ReworkCompleteRequest request) {
        return ApiResponse.success("返修完成结果已登记", service.complete(id, request));
    }

    @PostMapping("/round/{id}/withdraw")
    public ApiResponse<ReworkRound> withdraw(@PathVariable Long id,
                                             @RequestBody(required = false) ReworkWithdrawRequest request) {
        return ApiResponse.success("本轮完成结果已撤回", service.withdraw(id, request));
    }

    @PostMapping("/round/{id}/correction")
    public ApiResponse<ReworkCorrection> correction(@PathVariable Long id,
                                                    @RequestBody ReworkCorrectionRequest request) {
        return ApiResponse.success("更正说明已追加", service.addCorrection(id, request));
    }

    @PostMapping("/round/{id}/reinspect")
    public ApiResponse<Inspection> reinspect(@PathVariable Long id, @RequestBody ReinspectionRequest request) {
        return ApiResponse.success("复检结论已提交", service.reinspect(id, request));
    }
}
