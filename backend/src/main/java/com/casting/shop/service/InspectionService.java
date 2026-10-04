package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.ConflictException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Casting;
import com.casting.shop.domain.Defect;
import com.casting.shop.domain.Inspection;
import com.casting.shop.dto.DefectRequest;
import com.casting.shop.dto.InspectionRequest;
import com.casting.shop.repository.CastingRepository;
import com.casting.shop.repository.DefectRepository;
import com.casting.shop.repository.InspectionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 检验与缺陷。
 *
 * <p>规则：检验单号唯一；缺陷类型六选一；判定只能合格 / 返修 / 报废；
 * 判返修或报废必须至少一条缺陷记录；判报废的铸件不可再入库；只能对「待检验」的铸件开单。</p>
 */
@Service
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;
    private final CastingRepository castingRepository;
    private final ReworkService reworkService;

    public InspectionService(InspectionRepository inspectionRepository, DefectRepository defectRepository,
                             CastingRepository castingRepository, ReworkService reworkService) {
        this.inspectionRepository = inspectionRepository;
        this.defectRepository = defectRepository;
        this.castingRepository = castingRepository;
        this.reworkService = reworkService;
    }

    public List<Inspection> list() {
        return inspectionRepository.findAllByOrderByIdDesc();
    }

    public Inspection get(Long id) {
        return inspectionRepository.findById(id)
                .orElseThrow(() -> new BizException("检验单不存在：" + id));
    }

    public Map<String, Object> detail(Long id) {
        Inspection inspection = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inspection", inspection);
        data.put("defects", defectRepository.findByInspectIdOrderByIdAsc(id));
        data.put("casting", inspection.getCastingId() == null ? null
                : castingRepository.findById(inspection.getCastingId()).orElse(null));
        return data;
    }

    @Transactional
    public Inspection create(InspectionRequest request) {
        if (!StringUtils.hasText(request.getInspectNo())) {
            throw new BizException("检验单号不能为空");
        }
        if (inspectionRepository.existsByInspectNo(request.getInspectNo())) {
            throw new BizException("检验单号已存在：" + request.getInspectNo());
        }
        if (!Dict.isValidVerdict(request.getVerdict())) {
            throw new BizException("判定结论只能三选一（PASS / REWORK / SCRAP）：" + request.getVerdict());
        }
        List<DefectRequest> defects = request.getDefects() == null ? List.of() : request.getDefects();
        if (!"PASS".equals(request.getVerdict()) && defects.isEmpty()) {
            throw new BizException("判「" + Dict.verdictName(request.getVerdict()) + "」必须至少录入一条缺陷记录");
        }

        Casting casting = request.getCastingId() == null ? null
                : castingRepository.findLockedById(request.getCastingId())
                        .orElseThrow(() -> new BizException("铸件卡不存在：" + request.getCastingId()));
        if (casting == null) {
            throw new BizException("检验单必须挂到一张铸件卡上");
        }
        if (!"INSPECTING".equals(casting.getStatus())) {
            throw new ConflictException("铸件 " + casting.getCastingNo()
                    + " 已被其他操作者处理为「" + Dict.castingStatusName(casting.getStatus())
                    + "」，请刷新查看已生效记录");
        }

        Inspection inspection = new Inspection();
        inspection.setInspectNo(request.getInspectNo());
        inspection.setCastingId(casting.getId());
        inspection.setCastingNo(casting.getCastingNo());
        inspection.setInspector(request.getInspector());
        inspection.setHardnessHb(request.getHardnessHb());
        inspection.setVerdict(request.getVerdict());
        inspection.setRemark(request.getRemark());
        inspection.setInspectTime(LocalDateTime.now());
        inspection.setInspectionKind("INITIAL");
        inspection.setRoundNo(1);
        Inspection saved = inspectionRepository.save(inspection);

        List<Defect> savedDefects = new ArrayList<>();
        int count = 0;
        for (DefectRequest item : defects) {
            savedDefects.add(defectRepository.save(toDefect(saved.getId(), item)));
            count++;
        }
        saved.setDefectCount(count);
        saved = inspectionRepository.save(saved);
        if ("REWORK".equals(saved.getVerdict())) {
            // REWORK：入独立返修队列，同时复制首次缺陷快照；禁止由 casting.status 直接替代证据链。
            reworkService.startFromInitialInspection(saved, savedDefects);
        } else {
            applyVerdictToCasting(casting, saved.getVerdict());
        }
        return saved;
    }

    /** 检验看板：缺陷类型统计、判定分布、待检验铸件、六类缺陷字典 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inspections", inspectionRepository.findAllByOrderByIdDesc());
        data.put("defectStats", defectRepository.statByDefectType());
        data.put("verdictStats", inspectionRepository.statVerdict());
        List<Casting> waiting = castingRepository.listDerived().stream()
                .filter(c -> "INSPECTING".equals(c.getStatus()))
                .toList();
        data.put("waitingCastings", waiting);
        List<Map<String, Object>> defectTypes = new ArrayList<>();
        for (String type : Dict.DEFECT_TYPES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", type);
            item.put("name", Dict.defectTypeName(type));
            item.put("color", Dict.defectTypeColor(type));
            defectTypes.add(item);
        }
        data.put("defectTypes", defectTypes);
        data.put("severities", List.of(
                Map.of("code", "MINOR", "name", Dict.severityName("MINOR")),
                Map.of("code", "MAJOR", "name", Dict.severityName("MAJOR")),
                Map.of("code", "CRITICAL", "name", Dict.severityName("CRITICAL"))));
        return data;
    }

    /** 首检结论回写到铸件卡；返修必须走 {@link ReworkService#startFromInitialInspection} */
    private void applyVerdictToCasting(Casting casting, String verdict) {
        switch (verdict) {
            case "PASS" -> casting.setStatus("STORED");
            case "SCRAP" -> casting.setStatus("SCRAPPED");
            default -> throw new BizException("返修结论必须创建返修单，不能直接改铸件状态");
        }
        castingRepository.save(casting);
    }

    private Defect toDefect(Long inspectId, DefectRequest request) {
        if (!Dict.isValidDefectType(request.getDefectType())) {
            throw new BizException("缺陷类型非法，只能六选一：" + request.getDefectType());
        }
        if (!Dict.isValidSeverity(request.getSeverity())) {
            throw new BizException("严重度非法（MINOR / MAJOR / CRITICAL）");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new BizException("缺陷个数必须大于 0");
        }
        Defect defect = new Defect();
        defect.setInspectId(inspectId);
        defect.setDefectType(request.getDefectType());
        defect.setSeverity(request.getSeverity());
        defect.setPosition(request.getPosition());
        defect.setSizeMm(request.getSizeMm());
        defect.setQuantity(request.getQuantity());
        defect.setRemark(request.getRemark());
        return defect;
    }
}
