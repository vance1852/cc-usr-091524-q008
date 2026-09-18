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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 计量保养的事务边界：所有公共方法都只触碰“一个设备 + 一个计量项”，
 * 且事务内第一条语句即对 meter_definitions 行加 FOR UPDATE 行锁，
 * 从而在 InnoDB 可重复读下保证锁后普通查询能读到最新已提交数据，
 * 并发上报/换表/完工彼此串行化，避免重复开工单、周期错算。
 */
@Service
public class MeterTxService {

    static final List<String> ACTIVE_ORDER_STATUSES = List.of("open", "in_progress");
    static final List<String> OPEN_REMINDER_STATUSES =
            List.of(MaintenanceReminder.PENDING, MaintenanceReminder.CONFIRMED);

    private final MeterDefinitionRepository defRepo;
    private final MeterReadingRepository readingRepo;
    private final MeterReplacementRepository replacementRepo;
    private final MaintenanceBaselineRepository baselineRepo;
    private final MaintenanceReminderRepository reminderRepo;
    private final WorkOrderRepository workOrderRepo;
    private final EquipmentRepository equipmentRepo;

    public MeterTxService(MeterDefinitionRepository defRepo,
                          MeterReadingRepository readingRepo,
                          MeterReplacementRepository replacementRepo,
                          MaintenanceBaselineRepository baselineRepo,
                          MaintenanceReminderRepository reminderRepo,
                          WorkOrderRepository workOrderRepo,
                          EquipmentRepository equipmentRepo) {
        this.defRepo = defRepo;
        this.readingRepo = readingRepo;
        this.replacementRepo = replacementRepo;
        this.baselineRepo = baselineRepo;
        this.reminderRepo = reminderRepo;
        this.workOrderRepo = workOrderRepo;
        this.equipmentRepo = equipmentRepo;
    }

    /** 新增或更新某设备的计量项配置（同设备同 metric 唯一）。 */
    @Transactional
    public MeterDefinition saveDefinition(MeterService.DefinitionInput in) {
        Equipment equipment = equipmentRepo.findById(in.equipmentId()).orElse(null);
        if (equipment == null) {
            throw new MeterValidationException(404, "设备不存在");
        }
        String metric = in.metric() == null ? "" : in.metric().trim();
        if (!metric.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new MeterValidationException(422, "计量项编码须为小写字母开头的小写字母/数字/下划线组合");
        }
        if (in.threshold() == null || in.threshold().signum() <= 0) {
            throw new MeterValidationException(422, "保养阈值必须大于 0");
        }
        BigDecimal lead = in.lead() == null ? BigDecimal.ZERO : in.lead();
        if (lead.signum() < 0 || lead.compareTo(in.threshold()) >= 0) {
            throw new MeterValidationException(422, "提前量必须满足 0 ≤ 提前量 < 保养阈值");
        }
        MeterDefinition def = defRepo.findByEquipmentIdAndMetric(in.equipmentId(), metric)
                .orElseGet(MeterDefinition::new);
        def.setEquipmentId(in.equipmentId());
        def.setMetric(metric);
        String name = (in.name() == null || in.name().isBlank()) ? metric : in.name().trim();
        def.setName(name.length() > 64 ? name.substring(0, 64) : name);
        def.setUnit(in.unit());
        def.setThreshold(in.threshold());
        def.setLead(lead);
        if (in.enabled() != null) {
            def.setEnabled(in.enabled());
        } else if (def.getId() == null) {
            def.setEnabled(true);
        }
        try {
            return defRepo.save(def);
        } catch (DataIntegrityViolationException dup) {
            throw new MeterValidationException(409, "该设备已存在计量项 " + metric);
        }
    }

    /**
     * 处理同属一个设备计量项的一批读数（同组内按顺序串行）。
     * 调用方须保证 equipmentId/metric 已存在；定义不存在时整组拒绝。
     */
    @Transactional
    public List<MeterService.ReadingResult> ingestGroup(Long equipmentId, String metric,
                                                        List<MeterService.IndexedReading> items) {
        // 第一条语句即行锁
        MeterDefinition def = defRepo.lockByEquipmentAndMetric(equipmentId, metric).orElse(null);
        List<MeterService.ReadingResult> results = new ArrayList<>();
        if (def == null) {
            for (MeterService.IndexedReading ir : items) {
                results.add(reject(ir, "设备未配置计量项 " + metric));
            }
            return results;
        }
        if (Boolean.FALSE.equals(def.getEnabled())) {
            for (MeterService.IndexedReading ir : items) {
                results.add(reject(ir, "计量项 " + metric + " 已停用"));
            }
            return results;
        }

        MeterReplacement segment = replacementRepo
                .findTopByEquipmentIdAndMetricOrderByIdDesc(equipmentId, metric).orElse(null);
        MeterReading latest = readingRepo
                .findTopByEquipmentIdAndMetricOrderByIdDesc(equipmentId, metric).orElse(null);

        for (MeterService.IndexedReading ir : items) {
            MeterService.ReadingInput in = ir.input();
            String sourceSeq = in.sourceSeq() == null ? "" : in.sourceSeq().trim();
            if (sourceSeq.isEmpty()) {
                results.add(reject(ir, "来源序号必填"));
                continue;
            }
            BigDecimal display = in.value();
            if (display == null || display.signum() < 0) {
                results.add(reject(ir, "读数不能为空或负数"));
                continue;
            }

            // 来源序号去重
            MeterReading existing = readingRepo
                    .findByEquipmentIdAndMetricAndSourceSeq(equipmentId, metric, sourceSeq).orElse(null);
            if (existing != null) {
                results.add(duplicate(ir, existing));
                continue;
            }

            // 表显单调性校验（当前表段内非递减）
            String baseError = validateDisplayWithinSegment(def, segment, latest, display);
            if (baseError != null) {
                results.add(reject(ir, baseError));
                continue;
            }

            // 历史累计值 = 结转量 + (表显 - 新表起点)；无换表段时即表显本身
            BigDecimal cumulative = segment == null
                    ? display
                    : segment.getCarryOverValue().add(display.subtract(segment.getNewStartValue()));
            if (latest != null && cumulative.compareTo(latest.getCumulativeValue()) <= 0) {
                results.add(reject(ir, "读数未增长或属于已结账的旧表段：历史累计值 "
                        + cumulative.toPlainString() + " 不大于最新值 "
                        + latest.getCumulativeValue().toPlainString()
                        + "，换表/回绕请先登记授权换表"));
                continue;
            }

            MeterReading reading = new MeterReading();
            reading.setEquipmentId(equipmentId);
            reading.setMetric(metric);
            reading.setSourceSeq(sourceSeq);
            reading.setSegmentReplacementId(segment == null ? null : segment.getId());
            reading.setDisplayValue(display);
            reading.setCumulativeValue(cumulative);
            reading.setReadAt(in.readAt() == null ? LocalDateTime.now() : in.readAt());
            try {
                reading = readingRepo.saveAndFlush(reading);
            } catch (DataIntegrityViolationException dup) {
                // 唯一约束兜底并发重复来源序号
                MeterReading reread = readingRepo
                        .findByEquipmentIdAndMetricAndSourceSeq(equipmentId, metric, sourceSeq).orElse(null);
                if (reread != null) {
                    results.add(duplicate(ir, reread));
                } else {
                    results.add(reject(ir, "读数与已有记录冲突"));
                }
                continue;
            }
            latest = reading;

            EvalResult eval = evaluateThresholds(def, cumulative, "控制器读数 " + sourceSeq);
            results.add(new MeterService.ReadingResult(
                    ir.index(), equipmentId, in.equipmentCode(), metric, sourceSeq,
                    MeterService.ACCEPTED, null, reading.getId(),
                    cumulative, eval.workOrderId(), eval.workOrderCreated(), eval.reminderCreated()));
        }
        return results;
    }

    /** 授权换表：登记旧表终值、新表起点与历史结转量，不改动任何历史读数。 */
    @Transactional
    public MeterReplacement registerReplacement(Long equipmentId, String metric,
                                                 MeterService.ReplacementInput in, String username) {
        MeterDefinition def = defRepo.lockByEquipmentAndMetric(equipmentId, metric).orElse(null);
        if (def == null) {
            throw new MeterValidationException(404, "设备或计量项 " + metric + " 不存在");
        }
        if (in.oldFinalValue() == null || in.newStartValue() == null
                || in.oldFinalValue().signum() < 0 || in.newStartValue().signum() < 0) {
            throw new MeterValidationException(422, "旧表终值与新表起点必须为非负数");
        }
        MeterReplacement segment = replacementRepo
                .findTopByEquipmentIdAndMetricOrderByIdDesc(equipmentId, metric).orElse(null);
        MeterReading latest = readingRepo
                .findTopByEquipmentIdAndMetricOrderByIdDesc(equipmentId, metric).orElse(null);

        boolean latestInCurrentSegment = latest != null
                && (segment == null ? latest.getSegmentReplacementId() == null
                                   : segment.getId().equals(latest.getSegmentReplacementId()));
        BigDecimal carryOver;
        if (latestInCurrentSegment) {
            if (in.oldFinalValue().compareTo(latest.getDisplayValue()) != 0) {
                throw new MeterValidationException(422,
                        "旧表终值必须等于旧表最新表显读数 " + latest.getDisplayValue().toPlainString()
                                + "，以免丢失两次读数之间的用量");
            }
            carryOver = latest.getCumulativeValue();
        } else if (segment != null) {
            // 当前表段还没有任何读数：旧表停在上一段换表时的新表起点
            if (in.oldFinalValue().compareTo(segment.getNewStartValue()) != 0) {
                throw new MeterValidationException(422,
                        "当前表段无读数，旧表终值必须等于该表段起点 "
                                + segment.getNewStartValue().toPlainString());
            }
            carryOver = segment.getCarryOverValue();
        } else {
            // 从未上报过读数：历史从零起算
            carryOver = BigDecimal.ZERO;
        }

        MeterReplacement replacement = new MeterReplacement();
        replacement.setEquipmentId(equipmentId);
        replacement.setMetric(metric);
        replacement.setOldFinalValue(in.oldFinalValue());
        replacement.setNewStartValue(in.newStartValue());
        replacement.setCarryOverValue(carryOver);
        replacement.setAuthorizedBy(username);
        replacement.setReason(in.reason());
        replacement.setReplacedAt(LocalDateTime.now());
        replacement = replacementRepo.saveAndFlush(replacement);

        // 换表时刻按历史累计值复评一次阈值（阈值调整等情形下保持状态一致）
        evaluateThresholds(def, carryOver, "换表登记（" + replacement.getId() + "）");
        return replacement;
    }

    /**
     * 计量保养工单状态流转（仅在 meterMetric 非空时由工单接口委托）。
     * 完成时写入保养基准并关闭同周期提醒；基准按 workOrderId 幂等。
     */
    @Transactional
    public WorkOrder applyWorkOrderStatus(WorkOrder order, String newStatus) {
        // 先锁计量项，与上报/换表互斥
        MeterDefinition def = defRepo.lockByEquipmentAndMetric(order.getEquipmentId(), order.getMeterMetric())
                .orElse(null);
        boolean hasBaseline = baselineRepo.existsByWorkOrderId(order.getId());
        if (hasBaseline && !"done".equals(newStatus)) {
            throw new MeterValidationException(422,
                    "该计量保养工单已完成并记录保养基准，不能改回未完成状态");
        }
        order.setStatus(newStatus);
        order.setClosedAt("done".equals(newStatus) ? LocalDateTime.now() : null);
        workOrderRepo.save(order);

        if ("done".equals(newStatus) && def != null && !baselineRepo.existsByWorkOrderId(order.getId())) {
            MeterReading latest = readingRepo
                    .findTopByEquipmentIdAndMetricOrderByIdDesc(order.getEquipmentId(), order.getMeterMetric())
                    .orElse(null);
            BigDecimal baselineValue = latest != null ? latest.getCumulativeValue()
                    : (order.getMeterTriggerValue() == null ? BigDecimal.ZERO : order.getMeterTriggerValue());
            MaintenanceBaseline baseline = new MaintenanceBaseline();
            baseline.setEquipmentId(order.getEquipmentId());
            baseline.setMetric(order.getMeterMetric());
            baseline.setWorkOrderId(order.getId());
            baseline.setBaselineValue(baselineValue);
            baseline.setThresholdValue(def.getThreshold());
            baselineRepo.save(baseline);

            BigDecimal cycleStart = order.getCycleStartValue() == null
                    ? BigDecimal.ZERO : order.getCycleStartValue();
            List<MaintenanceReminder> open = reminderRepo
                    .findByEquipmentIdAndMetricAndStatusInOrderByIdDesc(
                            order.getEquipmentId(), order.getMeterMetric(), OPEN_REMINDER_STATUSES);
            for (MaintenanceReminder r : open) {
                if (r.getCycleStartValue().compareTo(cycleStart) == 0) {
                    r.setStatus(MaintenanceReminder.DISMISSED);
                    r.setHandledAt(LocalDateTime.now());
                    reminderRepo.save(r);
                }
            }
        }
        return order;
    }

    /** 待确认提醒的人工处理：confirm 确认 / dismiss 忽略。 */
    @Transactional
    public MaintenanceReminder resolveReminder(Long id, String status) {
        MaintenanceReminder reminder = reminderRepo.findById(id).orElse(null);
        if (reminder == null) {
            throw new MeterValidationException(404, "提醒不存在");
        }
        // 锁计量项行，与读数触发的提醒转换互斥
        defRepo.lockByEquipmentAndMetric(reminder.getEquipmentId(), reminder.getMetric());
        reminder = reminderRepo.findById(id).orElseThrow();
        if (!MaintenanceReminder.PENDING.equals(reminder.getStatus())
                && !MaintenanceReminder.CONFIRMED.equals(reminder.getStatus())) {
            throw new MeterValidationException(422, "提醒已处理，状态为 " + reminder.getStatus());
        }
        if (MaintenanceReminder.CONFIRMED.equals(status)) {
            reminder.setStatus(MaintenanceReminder.CONFIRMED);
        } else if (MaintenanceReminder.DISMISSED.equals(status)) {
            reminder.setStatus(MaintenanceReminder.DISMISSED);
        } else {
            throw new MeterValidationException(422, "不支持的处理动作");
        }
        reminder.setHandledAt(LocalDateTime.now());
        return reminderRepo.save(reminder);
    }

    // ===== 内部逻辑 =====

    private String validateDisplayWithinSegment(MeterDefinition def, MeterReplacement segment,
                                                MeterReading latest, BigDecimal display) {
        if (segment == null) {
            if (latest != null && display.compareTo(latest.getDisplayValue()) < 0) {
                return "表显读数回退：" + display.toPlainString() + " 小于最新读数 "
                        + latest.getDisplayValue().toPlainString() + "，计数器回绕或换表须先登记授权换表";
            }
            return null;
        }
        if (display.compareTo(segment.getNewStartValue()) < 0) {
            return "表显读数低于新表起点 " + segment.getNewStartValue().toPlainString();
        }
        boolean latestInSegment = latest != null && segment.getId().equals(latest.getSegmentReplacementId());
        if (latestInSegment && display.compareTo(latest.getDisplayValue()) < 0) {
            return "表显读数回退：" + display.toPlainString() + " 小于新表段最新读数 "
                    + latest.getDisplayValue().toPlainString();
        }
        return null;
    }

    /**
     * 按历史累计值评估提前提醒与阈值工单。全部基于持久化状态计算，无内存状态。
     * 周期起点取最近保养基准；累计值不高于基准的迟到读数在调用处即被挡下，不会进入下一周期判断。
     */
    private EvalResult evaluateThresholds(MeterDefinition def, BigDecimal cumulative, String source) {
        Long equipmentId = def.getEquipmentId();
        String metric = def.getMetric();
        BigDecimal cycleStart = baselineRepo
                .findTopByEquipmentIdAndMetricOrderByIdDesc(equipmentId, metric)
                .map(MaintenanceBaseline::getBaselineValue)
                .orElse(BigDecimal.ZERO);
        BigDecimal usage = cumulative.subtract(cycleStart);
        BigDecimal threshold = def.getThreshold();
        BigDecimal leadPoint = threshold.subtract(def.getLead() == null ? BigDecimal.ZERO : def.getLead());

        Optional<WorkOrder> activeCycleOrder = findActiveCycleOrder(equipmentId, metric, cycleStart);

        if (usage.compareTo(threshold) >= 0) {
            // 越过阈值且当前无同周期工单：自动创建一张 maintenance 工单
            WorkOrder order;
            boolean created;
            if (activeCycleOrder.isPresent()) {
                order = activeCycleOrder.get();
                created = false;
            } else {
                order = createThresholdWorkOrder(def, cycleStart, cumulative, usage, source);
                created = true;
            }
            // 同周期仍挂起的提醒转为已转工单
            List<MaintenanceReminder> open = reminderRepo
                    .findByEquipmentIdAndMetricAndStatusInOrderByIdDesc(
                            equipmentId, metric, OPEN_REMINDER_STATUSES);
            for (MaintenanceReminder r : open) {
                if (r.getCycleStartValue().compareTo(cycleStart) == 0) {
                    r.setStatus(MaintenanceReminder.CONVERTED);
                    r.setWorkOrderId(order.getId());
                    r.setHandledAt(LocalDateTime.now());
                    reminderRepo.save(r);
                }
            }
            return new EvalResult(order.getId(), created, false);
        }

        if (usage.compareTo(leadPoint) >= 0 && activeCycleOrder.isEmpty()) {
            boolean existsOpenReminder = reminderRepo
                    .findByEquipmentIdAndMetricAndStatusInOrderByIdDesc(
                            equipmentId, metric, OPEN_REMINDER_STATUSES)
                    .stream().anyMatch(r -> r.getCycleStartValue().compareTo(cycleStart) == 0);
            if (!existsOpenReminder) {
                MaintenanceReminder reminder = new MaintenanceReminder();
                reminder.setEquipmentId(equipmentId);
                reminder.setMetric(metric);
                reminder.setCycleStartValue(cycleStart);
                reminder.setTriggerValue(cumulative);
                reminder.setThresholdValue(threshold);
                reminder.setLeadValue(def.getLead() == null ? BigDecimal.ZERO : def.getLead());
                reminder.setStatus(MaintenanceReminder.PENDING);
                reminder.setTriggerSource(source);
                Equipment e = equipmentRepo.findById(equipmentId).orElse(null);
                String equipName = e == null ? String.valueOf(equipmentId) : e.getName();
                reminder.setMessage(equipName + " " + def.getName() + " 距保养仅剩 "
                        + threshold.subtract(usage).toPlainString()
                        + (def.getUnit() == null ? "" : " " + def.getUnit()) + "，请提前安排");
                reminderRepo.save(reminder);
                return new EvalResult(null, false, true);
            }
        }
        return new EvalResult(null, false, false);
    }

    private Optional<WorkOrder> findActiveCycleOrder(Long equipmentId, String metric, BigDecimal cycleStart) {
        return workOrderRepo
                .findByEquipmentIdAndTypeAndStatusInOrderByIdDesc(
                        equipmentId, "maintenance", ACTIVE_ORDER_STATUSES)
                .stream()
                .filter(w -> metric.equals(w.getMeterMetric())
                        && w.getCycleStartValue() != null
                        && w.getCycleStartValue().compareTo(cycleStart) == 0)
                .findFirst();
    }

    private WorkOrder createThresholdWorkOrder(MeterDefinition def, BigDecimal cycleStart,
                                               BigDecimal cumulative, BigDecimal usage, String source) {
        Equipment e = equipmentRepo.findById(def.getEquipmentId()).orElse(null);
        String equipName = e == null ? String.valueOf(def.getEquipmentId()) : e.getName();
        WorkOrder order = new WorkOrder();
        order.setEquipmentId(def.getEquipmentId());
        order.setTitle(equipName + " " + def.getName() + "达到保养阈值");
        order.setType("maintenance");
        order.setPriority("high");
        order.setStatus("open");
        order.setAssignee("");
        order.setDescription("计量项[" + def.getName() + "]周期累计已达 " + usage.toPlainString()
                + (def.getUnit() == null ? "" : " " + def.getUnit())
                + "，保养阈值 " + def.getThreshold().toPlainString()
                + "；历史累计值 " + cumulative.toPlainString()
                + "，周期起点 " + cycleStart.toPlainString() + "。触发来源：" + source);
        order.setMeterMetric(def.getMetric());
        order.setCycleStartValue(cycleStart);
        order.setMeterTriggerValue(cumulative);
        order.setMeterTriggerSource(source);
        return workOrderRepo.saveAndFlush(order);
    }

    private MeterService.ReadingResult reject(MeterService.IndexedReading ir, String reason) {
        MeterService.ReadingInput in = ir.input();
        return new MeterService.ReadingResult(ir.index(), in.equipmentId(),
                in.equipmentCode(), in.metric(), in.sourceSeq(), MeterService.REJECTED, reason,
                null, null, null, false, false);
    }

    private MeterService.ReadingResult duplicate(MeterService.IndexedReading ir, MeterReading existing) {
        return new MeterService.ReadingResult(ir.index(), existing.getEquipmentId(),
                ir.input().equipmentCode(), existing.getMetric(), existing.getSourceSeq(),
                MeterService.DUPLICATE, "来源序号重复，读数已存在", existing.getId(),
                existing.getCumulativeValue(), null, false, false);
    }

    private record EvalResult(Long workOrderId, boolean workOrderCreated, boolean reminderCreated) {}
}
