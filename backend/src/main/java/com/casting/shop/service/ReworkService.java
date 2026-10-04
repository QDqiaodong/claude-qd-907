package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.ConflictException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Casting;
import com.casting.shop.domain.Defect;
import com.casting.shop.domain.Inspection;
import com.casting.shop.domain.ReworkCorrection;
import com.casting.shop.domain.ReworkDefectSnapshot;
import com.casting.shop.domain.ReworkOrder;
import com.casting.shop.domain.ReworkRound;
import com.casting.shop.dto.DefectRequest;
import com.casting.shop.dto.ReinspectionRequest;
import com.casting.shop.dto.ReworkCompleteRequest;
import com.casting.shop.dto.ReworkCorrectionRequest;
import com.casting.shop.dto.ReworkWithdrawRequest;
import com.casting.shop.repository.CastingRepository;
import com.casting.shop.repository.DefectRepository;
import com.casting.shop.repository.InspectionRepository;
import com.casting.shop.repository.ReworkCorrectionRepository;
import com.casting.shop.repository.ReworkDefectSnapshotRepository;
import com.casting.shop.repository.ReworkOrderRepository;
import com.casting.shop.repository.ReworkRoundRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 返修处置与复检闭环。 */
@Service
public class ReworkService {

    private final ReworkOrderRepository orderRepository;
    private final ReworkRoundRepository roundRepository;
    private final ReworkDefectSnapshotRepository snapshotRepository;
    private final ReworkCorrectionRepository correctionRepository;
    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;
    private final CastingRepository castingRepository;

    public ReworkService(ReworkOrderRepository orderRepository,
                         ReworkRoundRepository roundRepository,
                         ReworkDefectSnapshotRepository snapshotRepository,
                         ReworkCorrectionRepository correctionRepository,
                         InspectionRepository inspectionRepository,
                         DefectRepository defectRepository,
                         CastingRepository castingRepository) {
        this.orderRepository = orderRepository;
        this.roundRepository = roundRepository;
        this.snapshotRepository = snapshotRepository;
        this.correctionRepository = correctionRepository;
        this.inspectionRepository = inspectionRepository;
        this.defectRepository = defectRepository;
        this.castingRepository = castingRepository;
    }

    /** 首次检验判定 REWORK 时在同一事务内创建独立返修单与第 1 轮待办。 */
    @Transactional
    public ReworkOrder startFromInitialInspection(Inspection inspection, List<Defect> defects) {
        if (defects == null || defects.isEmpty()) {
            throw new BizException("返修单必须保留至少一条首次缺陷快照");
        }
        orderRepository.findByInitialInspectionId(inspection.getId()).ifPresent(existing -> {
            throw new ConflictException("返修单已由其他操作者创建：" + existing.getReworkNo());
        });

        LocalDateTime now = LocalDateTime.now();
        ReworkOrder order = new ReworkOrder();
        order.setReworkNo(inspection.getInspectNo().replaceFirst("^I-", "RW-"));
        order.setCastingId(inspection.getCastingId());
        order.setCastingNo(inspection.getCastingNo());
        order.setInitialInspectionId(inspection.getId());
        order.setCurrentRoundNo(1);
        order.setStatus("ACTIVE");
        order.setCurrentStage("PENDING");
        order.setCreatedAt(now);
        ReworkOrder savedOrder = orderRepository.save(order);

        ReworkRound round = new ReworkRound();
        round.setReworkOrderId(savedOrder.getId());
        round.setCastingId(savedOrder.getCastingId());
        round.setRoundNo(1);
        round.setStage("PENDING");
        round.setSourceInspectionId(inspection.getId());
        round.setCreatedAt(now);
        ReworkRound savedRound = roundRepository.save(round);

        for (Defect defect : defects) {
            ReworkDefectSnapshot snapshot = new ReworkDefectSnapshot();
            snapshot.setReworkRoundId(savedRound.getId());
            snapshot.setSourceDefectId(defect.getId());
            copyDefect(defect, snapshot);
            snapshotRepository.save(snapshot);
        }

        Casting casting = castingRepository.findLockedById(inspection.getCastingId())
                .orElseThrow(() -> new BizException("铸件卡不存在：" + inspection.getCastingId()));
        casting.setStatus("REWORK");
        castingRepository.save(casting);
        return savedOrder;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> board() {
        List<ReworkOrder> orders = orderRepository.findAllByOrderByIdDesc();
        Map<Long, ReworkRound> latestRounds = loadLatestRounds(orders);
        Map<Long, Casting> castings = loadCastings(orders);
        List<Map<String, Object>> queue = new ArrayList<>();
        for (ReworkOrder order : orders) {
            queue.add(toQueueItem(order, latestRounds.get(order.getId()), castings.get(order.getCastingId())));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orders", queue);
        data.put("pendingCount", roundRepository.countByStage("PENDING"));
        data.put("waitReinspectCount", roundRepository.countByStage("WAIT_REINSPECT"));
        data.put("activeCount", orders.stream().filter(o -> "ACTIVE".equals(o.getStatus())).count());
        data.put("methods", Dict.REWORK_METHODS.stream().map(code -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", Dict.reworkMethodName(code));
            return item;
        }).toList());
        data.put("stages", List.of(
                stage("ALL", "全部阶段"),
                stage("PENDING", "待返修"),
                stage("WAIT_REINSPECT", "待复检"),
                stage("PASS", "复检合格"),
                stage("SCRAPPED", "复检报废")));
        return data;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long orderId) {
        ReworkOrder order = getOrder(orderId);
        List<ReworkRound> rounds = roundRepository.findByReworkOrderIdOrderByRoundNoAsc(orderId);
        Inspection initialInspection = inspectionRepository.findById(order.getInitialInspectionId()).orElse(null);

        List<Map<String, Object>> timeline = new ArrayList<>();
        if (initialInspection != null) {
            Map<String, Object> initial = new LinkedHashMap<>();
            initial.put("type", "INITIAL");
            initial.put("time", initialInspection.getInspectTime());
            initial.put("title", "首次检验判返修");
            initial.put("inspection", initialInspection);
            initial.put("defects", defectRepository.findByInspectIdOrderByIdAsc(initialInspection.getId()));
            timeline.add(initial);
        }

        for (ReworkRound round : rounds) {
            Map<String, Object> roundItem = new LinkedHashMap<>();
            roundItem.put("type", "REWORK");
            roundItem.put("time", round.getCompletedAt() == null ? round.getCreatedAt() : round.getCompletedAt());
            roundItem.put("title", "第 " + round.getRoundNo() + " 轮返修处置");
            roundItem.put("round", round);
            roundItem.put("snapshots", snapshotRepository.findByReworkRoundIdOrderByIdAsc(round.getId()));
            roundItem.put("corrections", correctionRepository.findByReworkRoundIdOrderByIdAsc(round.getId()));
            timeline.add(roundItem);

            if (round.getReinspectionId() != null) {
                Inspection reinspection = inspectionRepository.findById(round.getReinspectionId()).orElse(null);
                if (reinspection != null) {
                    Map<String, Object> inspectionItem = new LinkedHashMap<>();
                    inspectionItem.put("type", "REINSPECTION");
                    inspectionItem.put("time", reinspection.getInspectTime());
                    inspectionItem.put("title", "第 " + round.getRoundNo() + " 轮复检：" + Dict.verdictName(reinspection.getVerdict()));
                    inspectionItem.put("inspection", reinspection);
                    inspectionItem.put("defects", defectRepository.findByInspectIdOrderByIdAsc(reinspection.getId()));
                    timeline.add(inspectionItem);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", order);
        data.put("casting", castingRepository.findById(order.getCastingId()).orElse(null));
        data.put("initialInspection", initialInspection);
        data.put("initialDefects", initialInspection == null ? List.of()
                : defectRepository.findByInspectIdOrderByIdAsc(initialInspection.getId()));
        data.put("rounds", rounds);
        data.put("timeline", timeline);
        return data;
    }

    @Transactional
    public ReworkRound complete(Long roundId, ReworkCompleteRequest request) {
        Long orderId = getRound(roundId).getReworkOrderId();
        ReworkOrder lockedOrder = orderRepository.findLockedById(orderId)
                .orElseThrow(() -> new BizException("返修单不存在：" + orderId));
        ReworkRound round = roundRepository.findLockedById(roundId)
                .orElseThrow(() -> new BizException("返修轮次不存在：" + roundId));
        if (!"PENDING".equals(round.getStage())) {
            if (round.getReinspectionId() != null) {
                throw new ConflictException("第 " + round.getRoundNo() + " 轮复检已创建，不能撤回完成结果，只能追加更正说明");
            }
            ReworkRound latest = roundRepository.findFirstByReworkOrderIdOrderByRoundNoDesc(round.getReworkOrderId())
                    .orElse(round);
            throw new ConflictException("第 " + round.getRoundNo() + " 轮已不是待返修，当前最新为第 "
                    + latest.getRoundNo() + " 轮「" + latest.getStageName() + "」；请刷新后处理最新待办");
        }
        if (!StringUtils.hasText(request.getResponsiblePerson())) {
            throw new BizException("责任人不能为空");
        }
        if (!Dict.isValidReworkMethod(request.getDispositionMethod())) {
            throw new BizException("处置方式非法：" + request.getDispositionMethod());
        }
        if (!StringUtils.hasText(request.getDispositionNote())) {
            throw new BizException("处置说明不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        round.setResponsiblePerson(request.getResponsiblePerson().trim());
        round.setDispositionMethod(request.getDispositionMethod());
        round.setDispositionNote(request.getDispositionNote().trim());
        round.setCompletedBy(StringUtils.hasText(request.getCompletedBy()) ? request.getCompletedBy().trim()
                : request.getResponsiblePerson().trim());
        round.setCompletedAt(request.getCompletedAt() == null ? now : request.getCompletedAt());
        round.setStage("WAIT_REINSPECT");
        ReworkRound saved = roundRepository.save(round);

        lockedOrder.setCurrentStage("WAIT_REINSPECT");
        orderRepository.save(lockedOrder);
        updateCastingStatus(round.getCastingId(), "WAIT_REINSPECT");
        return saved;
    }

    /** 复检单尚未创建时允许撤回本轮完成结果；原登记值保留作误填证据，仅记录撤回审计并回到待返修。 */
    @Transactional
    public ReworkRound withdraw(Long roundId, ReworkWithdrawRequest request) {
        Long orderId = getRound(roundId).getReworkOrderId();
        ReworkOrder lockedOrder = orderRepository.findLockedById(orderId)
                .orElseThrow(() -> new BizException("返修单不存在：" + orderId));
        ReworkRound round = roundRepository.findLockedById(roundId)
                .orElseThrow(() -> new BizException("返修轮次不存在：" + roundId));
        if (!"WAIT_REINSPECT".equals(round.getStage()) || round.getReinspectionId() != null) {
            throw new ConflictException("第 " + round.getRoundNo() + " 轮复检已创建或已终结，不能撤回完成结果，只能追加更正说明");
        }
        round.setStage("PENDING");
        round.setWithdrawnBy(request == null || !StringUtils.hasText(request.getOperator())
                ? "现场人员" : request.getOperator().trim());
        round.setWithdrawnAt(LocalDateTime.now());
        ReworkRound saved = roundRepository.save(round);

        lockedOrder.setCurrentStage("PENDING");
        orderRepository.save(lockedOrder);
        updateCastingStatus(round.getCastingId(), "REWORK");
        return saved;
    }

    /** 复检已创建后只允许追加更正，不能覆盖责任人、方式、完成时间和说明。 */
    @Transactional
    public ReworkCorrection addCorrection(Long roundId, ReworkCorrectionRequest request) {
        ReworkRound round = roundRepository.findLockedById(roundId)
                .orElseThrow(() -> new BizException("返修轮次不存在：" + roundId));
        if (round.getReinspectionId() == null) {
            if ("WAIT_REINSPECT".equals(round.getStage())) {
                throw new BizException("复检尚未创建，如属误填可直接撤回本轮完成结果");
            }
            throw new BizException("当前轮次尚未完成返修，不能追加更正");
        }
        if (request == null || !StringUtils.hasText(request.getCorrectionNote())) {
            throw new BizException("更正说明不能为空");
        }
        ReworkCorrection correction = new ReworkCorrection();
        correction.setReworkRoundId(round.getId());
        correction.setCorrectionNote(request.getCorrectionNote().trim());
        correction.setOperator(StringUtils.hasText(request.getOperator()) ? request.getOperator().trim() : "现场人员");
        correction.setCreatedAt(LocalDateTime.now());
        return correctionRepository.save(correction);
    }

    @Transactional
    public Inspection reinspect(Long roundId, ReinspectionRequest request) {
        ReworkRound orderRound = getRound(roundId);
        ReworkOrder order = orderRepository.findLockedById(orderRound.getReworkOrderId())
                .orElseThrow(() -> new BizException("返修单不存在：" + orderRound.getReworkOrderId()));
        List<ReworkRound> lockedRounds = roundRepository.findLockedByOrderId(order.getId());
        ReworkRound round = lockedRounds.stream().filter(r -> r.getId().equals(roundId)).findFirst()
                .orElseThrow(() -> new BizException("返修轮次不存在：" + roundId));
        if (!"WAIT_REINSPECT".equals(round.getStage())) {
            if (round.getReinspectionId() != null) {
                throw new ConflictException("第 " + round.getRoundNo() + " 轮复检已由检验员 "
                        + describeActiveOperator(round) + " 创建，不能重复发起；请刷新查看结果");
            }
            ReworkRound latest = roundRepository.findFirstByReworkOrderIdOrderByRoundNoDesc(round.getReworkOrderId())
                    .orElse(round);
            throw new ConflictException("第 " + round.getRoundNo() + " 轮已不能发起复检，当前最新为第 "
                    + latest.getRoundNo() + " 轮「" + latest.getStageName() + "」；请刷新后处理最新待办");
        }
        if (request == null || !Dict.isValidVerdict(request.getVerdict())) {
            throw new BizException("复检结论只能是合格 / 再次返修 / 报废");
        }

        List<ReworkDefectSnapshot> snapshots = snapshotRepository.findByReworkRoundIdOrderByIdAsc(roundId);
        List<DefectRequest> requestedDefects = request.getDefects() == null ? List.of() : request.getDefects();
        if (!"PASS".equals(request.getVerdict()) && requestedDefects.isEmpty()) {
            throw new BizException("再次返修或报废必须至少勾选上一轮缺陷中的一条");
        }
        if ("PASS".equals(request.getVerdict()) && !requestedDefects.isEmpty()) {
            throw new BizException("复检合格不能登记缺陷；仍有缺陷时应判再次返修或报废");
        }
        Set<Long> snapshotIds = new HashSet<>(snapshots.stream().map(ReworkDefectSnapshot::getId).toList());
        for (DefectRequest item : requestedDefects) {
            validateDefect(item);
            if (item.getSourceSnapshotId() == null || !snapshotIds.contains(item.getSourceSnapshotId())) {
                throw new BizException("复检只能引用本轮上一轮缺陷快照，不能新增无关缺陷");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        Inspection inspection = new Inspection();
        inspection.setInspectNo(generateReinspectionNo());
        inspection.setCastingId(round.getCastingId());
        ReworkOrder order = getOrder(round.getReworkOrderId());
        inspection.setCastingNo(order.getCastingNo());
        inspection.setInspector(request.getInspector());
        inspection.setHardnessHb(request.getHardnessHb());
        inspection.setVerdict(request.getVerdict());
        inspection.setRemark(request.getRemark());
        inspection.setInspectTime(now);
        inspection.setInspectionKind("REINSPECTION");
        inspection.setReworkOrderId(order.getId());
        inspection.setReworkRoundId(round.getId());
        inspection.setRoundNo(round.getRoundNo());
        Inspection savedInspection = inspectionRepository.save(inspection);

        int defectCount = 0;
        for (DefectRequest item : requestedDefects) {
            Defect defect = toDefect(savedInspection.getId(), item);
            defect.setSourceSnapshotId(item.getSourceSnapshotId());
            defectRepository.save(defect);
            defectCount++;
        }
        savedInspection.setDefectCount(defectCount);
        inspectionRepository.save(savedInspection);

        round.setReinspectionId(savedInspection.getId());
        if ("PASS".equals(request.getVerdict())) {
            round.setStage("PASS");
            order.setStatus("PASS");
            order.setCurrentStage("PASS");
            order.setClosedAt(now);
            updateCastingStatus(round.getCastingId(), "STORED");
        } else if ("SCRAP".equals(request.getVerdict())) {
            round.setStage("SCRAPPED");
            order.setStatus("SCRAPPED");
            order.setCurrentStage("SCRAPPED");
            order.setClosedAt(now);
            updateCastingStatus(round.getCastingId(), "SCRAPPED");
        } else if ("REWORK".equals(request.getVerdict())) {
            round.setStage("REINSPECTED");
            int nextNo = round.getRoundNo() + 1;
            order.setCurrentRoundNo(nextNo);
            order.setCurrentStage("PENDING");

            ReworkRound nextRound = new ReworkRound();
            nextRound.setReworkOrderId(order.getId());
            nextRound.setCastingId(round.getCastingId());
            nextRound.setRoundNo(nextNo);
            nextRound.setStage("PENDING");
            nextRound.setSourceInspectionId(savedInspection.getId());
            nextRound.setCreatedAt(now);
            ReworkRound savedNext = roundRepository.save(nextRound);
            copyRoundSnapshots(savedNext.getId(), requestedDefects, snapshots);
            updateCastingStatus(round.getCastingId(), "REWORK");
        }
        orderRepository.save(order);
        roundRepository.save(round);
        return savedInspection;
    }

    private void copyRoundSnapshots(Long nextRoundId, List<DefectRequest> requestedDefects,
                                    List<ReworkDefectSnapshot> previousSnapshots) {
        Map<Long, ReworkDefectSnapshot> previousMap = new LinkedHashMap<>();
        previousSnapshots.forEach(s -> previousMap.put(s.getId(), s));
        for (DefectRequest request : requestedDefects) {
            ReworkDefectSnapshot source = previousMap.get(request.getSourceSnapshotId());
            if (source == null) {
                throw new BizException("缺陷快照不存在：" + request.getSourceSnapshotId());
            }
            ReworkDefectSnapshot snapshot = new ReworkDefectSnapshot();
            snapshot.setReworkRoundId(nextRoundId);
            snapshot.setSourceSnapshotId(source.getId());
            snapshot.setDefectType(request.getDefectType());
            snapshot.setSeverity(request.getSeverity());
            snapshot.setPosition(request.getPosition());
            snapshot.setSizeMm(request.getSizeMm());
            snapshot.setQuantity(request.getQuantity());
            snapshot.setRemark(request.getRemark());
            snapshotRepository.save(snapshot);
        }
    }

    private ReworkOrder getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BizException("返修单不存在：" + id));
    }

    private ReworkRound getRound(Long id) {
        return roundRepository.findById(id)
                .orElseThrow(() -> new BizException("返修轮次不存在：" + id));
    }

    private Map<Long, ReworkRound> loadLatestRounds(List<ReworkOrder> orders) {
        Map<Long, ReworkRound> result = new LinkedHashMap<>();
        for (ReworkOrder order : orders) {
            roundRepository.findFirstByReworkOrderIdOrderByRoundNoDesc(order.getId())
                    .ifPresent(round -> result.put(order.getId(), round));
        }
        return result;
    }

    private Map<Long, Casting> loadCastings(List<ReworkOrder> orders) {
        Map<Long, Casting> result = new LinkedHashMap<>();
        orders.stream().map(ReworkOrder::getCastingId).filter(Objects::nonNull).distinct().forEach(id ->
                castingRepository.findById(id).ifPresent(casting -> result.put(id, casting)));
        return result;
    }

    private Map<String, Object> toQueueItem(ReworkOrder order, ReworkRound latestRound, Casting casting) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("order", order);
        item.put("casting", casting);
        item.put("latestRound", latestRound);
        item.put("roundNo", latestRound == null ? order.getCurrentRoundNo() : latestRound.getRoundNo());
        item.put("stage", latestRound == null ? order.getCurrentStage() : displayStage(latestRound));
        item.put("responsiblePerson", latestRound == null ? null : latestRound.getResponsiblePerson());
        item.put("dispositionMethod", latestRound == null ? null : latestRound.getDispositionMethod());
        item.put("dispositionMethodName", latestRound == null ? null : latestRound.getDispositionMethodName());
        item.put("completedAt", latestRound == null ? null : latestRound.getCompletedAt());
        item.put("completedBy", latestRound == null ? null : latestRound.getCompletedBy());
        item.put("defectCount", latestRound == null ? 0
                : snapshotRepository.findByReworkRoundIdOrderByIdAsc(latestRound.getId()).size());
        return item;
    }

    /** 旧轮 REINSPECTED 保留在时间线；队列只暴露最新轮的待返修/待复检/终态。 */
    private String displayStage(ReworkRound round) {
        return round.getStage();
    }

    private Map<String, String> stage(String code, String name) {
        return Map.of("code", code, "name", name);
    }

    private void updateCastingStatus(Long castingId, String status) {
        Casting casting = castingRepository.findLockedById(castingId)
                .orElseThrow(() -> new BizException("铸件卡不存在：" + castingId));
        casting.setStatus(status);
        castingRepository.save(casting);
    }

    private String generateReinspectionNo() {
        for (int i = 0; i < 5; i++) {
            String candidate = "RI-" + (System.currentTimeMillis() + i);
            if (!inspectionRepository.existsByInspectNo(candidate)) {
                return candidate;
            }
        }
        throw new BizException("复检单号生成冲突，请重试");
    }

    private String describeActiveOperator(ReworkRound round) {
        if (round == null) {
            return "已生效操作者";
        }
        if (round.getReinspectionId() != null) {
            return inspectionRepository.findById(round.getReinspectionId())
                    .map(Inspection::getInspector).orElse("已生效检验员");
        }
        if (StringUtils.hasText(round.getCompletedBy())) {
            return round.getCompletedBy();
        }
        return "已生效操作者";
    }

    private void copyDefect(Defect source, ReworkDefectSnapshot target) {
        target.setDefectType(source.getDefectType());
        target.setSeverity(source.getSeverity());
        target.setPosition(source.getPosition());
        target.setSizeMm(source.getSizeMm());
        target.setQuantity(source.getQuantity());
        target.setRemark(source.getRemark());
    }

    private void validateDefect(DefectRequest request) {
        if (!Dict.isValidDefectType(request.getDefectType())) {
            throw new BizException("缺陷类型非法，只能六选一：" + request.getDefectType());
        }
        if (!Dict.isValidSeverity(request.getSeverity())) {
            throw new BizException("严重度非法（MINOR / MAJOR / CRITICAL）");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new BizException("缺陷个数必须大于 0");
        }
    }

    private Defect toDefect(Long inspectionId, DefectRequest request) {
        validateDefect(request);
        Defect defect = new Defect();
        defect.setInspectId(inspectionId);
        defect.setDefectType(request.getDefectType());
        defect.setSeverity(request.getSeverity());
        defect.setPosition(request.getPosition());
        defect.setSizeMm(request.getSizeMm());
        defect.setQuantity(request.getQuantity());
        defect.setRemark(request.getRemark());
        return defect;
    }
}
