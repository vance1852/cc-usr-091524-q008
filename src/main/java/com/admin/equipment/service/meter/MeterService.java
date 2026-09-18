package com.admin.equipment.service.meter;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.meter.MaintenanceBaseline;
import com.admin.equipment.model.meter.MaintenanceReminder;
import com.admin.equipment.model.meter.MeterDefinition;
import com.admin.equipment.model.meter.MeterReading;
import com.admin.equipment.model.meter.MeterReplacement;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.meter.MaintenanceBaselineRepository;
import com.admin.equipment.repo.meter.MaintenanceReminderRepository;
import com.admin.equipment.repo.meter.MeterDefinitionRepository;
import com.admin.equipment.repo.meter.MeterReadingRepository;
import com.admin.equipment.repo.meter.MeterReplacementRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运行计量驱动保养的编排与查询服务。
 * 批量上报按 (设备, 计量项) 分组，每组一个独立事务交由 {@link MeterTxService} 串行处理；
 * 所有阈值判断只依赖数据库中持久化的历史累计值与基准，进程重启后结果不变。
 */
@Service
public class MeterService {

    public static final String ACCEPTED = "accepted";
    public static final String DUPLICATE = "duplicate";
    public static final String REJECTED = "rejected";

    private final MeterDefinitionRepository defRepo;
    private final MeterReadingRepository readingRepo;
    private final MeterReplacementRepository replacementRepo;
    private final MaintenanceBaselineRepository baselineRepo;
    private final MaintenanceReminderRepository reminderRepo;
    private final WorkOrderRepository workOrderRepo;
    private final EquipmentRepository equipmentRepo;
    private final MeterTxService txService;

    public MeterService(MeterDefinitionRepository defRepo,
                        MeterReadingRepository readingRepo,
                        MeterReplacementRepository replacementRepo,
                        MaintenanceBaselineRepository baselineRepo,
                        MaintenanceReminderRepository reminderRepo,
                        WorkOrderRepository workOrderRepo,
                        EquipmentRepository equipmentRepo,
                        MeterTxService txService) {
        this.defRepo = defRepo;
        this.readingRepo = readingRepo;
        this.replacementRepo = replacementRepo;
        this.baselineRepo = baselineRepo;
        this.reminderRepo = reminderRepo;
        this.workOrderRepo = workOrderRepo;
        this.equipmentRepo = equipmentRepo;
        this.txService = txService;
    }

    // ===== 请求/响应记录 =====

    public record DefinitionInput(Long equipmentId, String metric, String name, String unit,
                                  BigDecimal threshold, BigDecimal lead, Boolean enabled) {}

    public record ReadingInput(Long equipmentId, String equipmentCode, String metric,
                               String sourceSeq, BigDecimal value, LocalDateTime readAt) {}

    public record ReplacementInput(BigDecimal oldFinalValue, BigDecimal newStartValue, String reason) {}

    /** 批内带序号的读数，分组处理后按序号归位。 */
    public record IndexedReading(int index, ReadingInput input) {}

    public record ReadingResult(int index, Long equipmentId, String equipmentCode, String metric,
                                String sourceSeq, String status, String reason,
                                Long readingId, BigDecimal cumulativeValue,
                                Long workOrderId, boolean workOrderCreated, boolean reminderCreated) {}

    public record MetricStatus(Long equipmentId, String equipmentCode, String equipmentName,
                               String metric, String name, String unit, boolean enabled,
                               BigDecimal threshold, BigDecimal lead, BigDecimal leadPoint,
                               BigDecimal latestCumulativeValue, BigDecimal latestDisplayValue,
                               String latestSourceSeq, LocalDateTime latestReadAt,
                               Long segmentReplacementId,
                               BigDecimal cycleStartValue, BigDecimal cycleUsage, BigDecimal remaining,
                               String state, boolean dueSoon, boolean overdue,
                               Long workOrderId, String workOrderStatus, String workOrderTitle,
                               Long reminderId, String reminderStatus, String triggerSource) {}

    public record EquipmentMeterStatus(Equipment equipment, List<MetricStatus> metrics) {}

    // ===== 计量项配置 =====

    public List<MeterDefinition> listDefinitions(Long equipmentId) {
        if (equipmentId != null) {
            return defRepo.findByEquipmentIdOrderByIdAsc(equipmentId);
        }
        return defRepo.findAll();
    }

    public MeterDefinition saveDefinition(DefinitionInput in) {
        return txService.saveDefinition(in);
    }

    public void deleteDefinition(Long id) {
        MeterDefinition def = defRepo.findById(id)
                .orElseThrow(() -> new MeterValidationException(404, "计量项配置不存在"));
        if (readingRepo.existsByEquipmentIdAndMetric(def.getEquipmentId(), def.getMetric())
                || baselineRepo.findTopByEquipmentIdAndMetricOrderByIdDesc(
                        def.getEquipmentId(), def.getMetric()).isPresent()) {
            throw new MeterValidationException(422, "该计量项已有读数或保养记录，不能删除，可改为停用");
        }
        defRepo.delete(def);
    }

    // ===== 批量读数上报 =====

    public List<ReadingResult> ingestBatch(List<ReadingInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new MeterValidationException(422, "读数列表不能为空");
        }
        if (inputs.size() > 1000) {
            throw new MeterValidationException(422, "单批读数不能超过 1000 条");
        }

        List<ReadingResult> immediate = new ArrayList<>();
        // 保持分组出现顺序与组内原始顺序
        Map<String, List<IndexedReading>> groups = new LinkedHashMap<>();

        for (int i = 0; i < inputs.size(); i++) {
            int index = i + 1;
            ReadingInput in = inputs.get(i) == null ? new ReadingInput(null, null, null, null, null, null)
                    : inputs.get(i);
            Long equipmentId = resolveEquipmentId(in);
            String metric = in.metric() == null ? "" : in.metric().trim();
            if (equipmentId == null) {
                immediate.add(new ReadingResult(index, in.equipmentId(), in.equipmentCode(), metric,
                        in.sourceSeq(), REJECTED, "设备不存在或未指定设备",
                        null, null, null, false, false));
                continue;
            }
            if (metric.isEmpty()) {
                immediate.add(new ReadingResult(index, equipmentId, in.equipmentCode(), "",
                        in.sourceSeq(), REJECTED, "计量项编码必填",
                        null, null, null, false, false));
                continue;
            }
            groups.computeIfAbsent(equipmentId + "|" + metric, k -> new ArrayList<>())
                    .add(new IndexedReading(index,
                            new ReadingInput(equipmentId, in.equipmentCode(), metric,
                                    in.sourceSeq(), in.value(), in.readAt())));
        }

        List<ReadingResult> results = new ArrayList<>(immediate);
        for (Map.Entry<String, List<IndexedReading>> entry : groups.entrySet()) {
            List<IndexedReading> group = entry.getValue();
            Long equipmentId = group.get(0).input().equipmentId();
            String metric = group.get(0).input().metric();
            try {
                results.addAll(txService.ingestGroup(equipmentId, metric, group));
            } catch (RuntimeException ex) {
                // 单组失败不影响其它设备/计量项
                String reason = ex.getMessage() == null ? "处理失败" : ex.getMessage();
                for (IndexedReading ir : group) {
                    results.add(new ReadingResult(ir.index(), equipmentId,
                            ir.input().equipmentCode(), metric, ir.input().sourceSeq(),
                            REJECTED, reason, null, null, null, false, false));
                }
            }
        }
        results.sort(Comparator.comparingInt(ReadingResult::index));
        return results;
    }

    private Long resolveEquipmentId(ReadingInput in) {
        if (in.equipmentId() != null) {
            return equipmentRepo.existsById(in.equipmentId()) ? in.equipmentId() : null;
        }
        if (in.equipmentCode() != null && !in.equipmentCode().isBlank()) {
            return equipmentRepo.findByCode(in.equipmentCode().trim()).map(Equipment::getId).orElse(null);
        }
        return null;
    }

    // ===== 授权换表 =====

    public MeterReplacement registerReplacement(Long equipmentId, String metric,
                                                ReplacementInput in, String username) {
        if (equipmentRepo.existsById(equipmentId)
                && defRepo.existsByEquipmentIdAndMetric(equipmentId, norm(metric))) {
            return txService.registerReplacement(equipmentId, norm(metric), in, username);
        }
        throw new MeterValidationException(404, "设备或计量项不存在");
    }

    public List<MeterReplacement> listReplacements(Long equipmentId, String metric) {
        if (equipmentId == null || metric == null || metric.isBlank()) {
            return replacementRepo.findAll();
        }
        return replacementRepo.findByEquipmentIdAndMetricOrderByIdAsc(equipmentId, metric.trim());
    }

    public List<MeterReading> listReadings(Long equipmentId, String metric, int limit) {
        List<MeterReading> all = readingRepo.findByEquipmentIdAndMetricOrderByIdAsc(equipmentId, metric.trim());
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(all.size() - limit, all.size());
    }

    // ===== 剩余量与触发来源 =====

    public EquipmentMeterStatus equipmentStatus(Long equipmentId) {
        Equipment equipment = equipmentRepo.findById(equipmentId)
                .orElseThrow(() -> new MeterValidationException(404, "设备不存在"));
        List<MetricStatus> metrics = new ArrayList<>();
        for (MeterDefinition def : defRepo.findByEquipmentIdOrderByIdAsc(equipmentId)) {
            metrics.add(buildMetricStatus(equipment, def));
        }
        return new EquipmentMeterStatus(equipment, metrics);
    }

    public List<EquipmentMeterStatus> allStatuses() {
        List<EquipmentMeterStatus> out = new ArrayList<>();
        for (Equipment e : equipmentRepo.findAll()) {
            List<MeterDefinition> defs = defRepo.findByEquipmentIdOrderByIdAsc(e.getId());
            // 未配置计量项的种子设备：metrics 为空，保持兼容
            List<MetricStatus> metrics = defs.stream().map(d -> buildMetricStatus(e, d)).toList();
            out.add(new EquipmentMeterStatus(e, metrics));
        }
        return out;
    }

    private MetricStatus buildMetricStatus(Equipment equipment, MeterDefinition def) {
        Long equipmentId = equipment.getId();
        String metric = def.getMetric();
        MeterReading latest = readingRepo
                .findTopByEquipmentIdAndMetricOrderByIdDesc(equipmentId, metric).orElse(null);
        MeterReplacement segment = replacementRepo
                .findTopByEquipmentIdAndMetricOrderByIdDesc(equipmentId, metric).orElse(null);
        BigDecimal cycleStart = baselineRepo
                .findTopByEquipmentIdAndMetricOrderByIdDesc(equipmentId, metric)
                .map(MaintenanceBaseline::getBaselineValue)
                .orElse(BigDecimal.ZERO);
        BigDecimal threshold = def.getThreshold();
        BigDecimal lead = def.getLead() == null ? BigDecimal.ZERO : def.getLead();
        BigDecimal leadPoint = threshold.subtract(lead);

        BigDecimal cumulative = latest == null ? null : latest.getCumulativeValue();
        BigDecimal usage = cumulative == null ? null : cumulative.subtract(cycleStart);
        BigDecimal remaining = usage == null ? threshold : threshold.subtract(usage);

        String state = "no_data";
        boolean dueSoon = false;
        boolean overdue = false;
        Long workOrderId = null;
        String workOrderStatus = null;
        String workOrderTitle = null;
        Long reminderId = null;
        String reminderStatus = null;
        String triggerSource = latest == null ? "" : "控制器读数 " + latest.getSourceSeq();

        if (latest != null) {
            Optional<WorkOrder> activeOrder = workOrderRepo
                    .findByEquipmentIdAndTypeAndStatusInOrderByIdDesc(
                            equipmentId, "maintenance", MeterTxService.ACTIVE_ORDER_STATUSES)
                    .stream()
                    .filter(w -> metric.equals(w.getMeterMetric())
                            && w.getCycleStartValue() != null
                            && w.getCycleStartValue().compareTo(cycleStart) == 0)
                    .findFirst();
            if (activeOrder.isPresent()) {
                WorkOrder w = activeOrder.get();
                state = "due";
                overdue = true;
                workOrderId = w.getId();
                workOrderStatus = w.getStatus();
                workOrderTitle = w.getTitle();
                triggerSource = w.getMeterTriggerSource();
            } else if (usage.compareTo(threshold) >= 0) {
                // 阈值已越界但工单尚未生成（理论上上报事务会即时生成，此处为防御性展示）
                state = "due";
                overdue = true;
            } else if (usage.compareTo(leadPoint) >= 0) {
                state = "due_soon";
                dueSoon = true;
            } else {
                state = "normal";
            }

            MaintenanceReminder reminder = reminderRepo
                    .findByEquipmentIdAndMetricAndStatusInOrderByIdDesc(
                            equipmentId, metric, MeterTxService.OPEN_REMINDER_STATUSES)
                    .stream()
                    .filter(r -> r.getCycleStartValue().compareTo(cycleStart) == 0)
                    .findFirst().orElse(null);
            if (reminder != null) {
                reminderId = reminder.getId();
                reminderStatus = reminder.getStatus();
                if (workOrderId == null) {
                    triggerSource = reminder.getTriggerSource();
                }
            }
        }

        return new MetricStatus(
                equipmentId, equipment.getCode(), equipment.getName(),
                metric, def.getName(), def.getUnit(), !Boolean.FALSE.equals(def.getEnabled()),
                threshold, lead, leadPoint,
                cumulative, latest == null ? null : latest.getDisplayValue(),
                latest == null ? null : latest.getSourceSeq(),
                latest == null ? null : latest.getReadAt(),
                segment == null ? null : segment.getId(),
                cycleStart, usage, remaining,
                state, dueSoon, overdue,
                workOrderId, workOrderStatus, workOrderTitle,
                reminderId, reminderStatus, triggerSource);
    }

    // ===== 提醒 =====

    public List<MaintenanceReminder> listReminders(String status) {
        if (status == null || status.isBlank()) {
            return reminderRepo.findAllByOrderByIdDesc();
        }
        return reminderRepo.findByStatusOrderByIdDesc(status.trim());
    }

    public MaintenanceReminder resolveReminder(Long id, String action) {
        String target = switch (action == null ? "" : action.trim().toLowerCase()) {
            case "confirm", "confirmed" -> MaintenanceReminder.CONFIRMED;
            case "dismiss", "dismissed" -> MaintenanceReminder.DISMISSED;
            default -> throw new MeterValidationException(422, "动作仅支持 confirm 或 dismiss");
        };
        return txService.resolveReminder(id, target);
    }

    public long pendingReminderCount() {
        return reminderRepo.countByStatus(MaintenanceReminder.PENDING);
    }

    private String norm(String metric) {
        return metric == null ? "" : metric.trim();
    }
}
