package com.admin.equipment.service.metering;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.metering.*;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.metering.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 单条读数摄入，独立事务提交：批量中一条被拒不影响其他条。
 * 所有计量状态变更（上报/换表/工单关闭）均先对计量定义行加悲观写锁，
 * 因此并发与重启后的判定结果一致。
 */
@Service
public class ReadingIngestor {

    private static final List<String> OPEN_STATUSES = List.of("open", "in_progress");

    private final MeterDefinitionRepository meterRepo;
    private final MeterReadingRepository readingRepo;
    private final MeterReplacementRepository replacementRepo;
    private final MaintenanceBaselineRepository baselineRepo;
    private final MaintenanceReminderRepository reminderRepo;
    private final WorkOrderRepository workOrderRepo;
    private final EquipmentRepository equipmentRepo;

    public ReadingIngestor(MeterDefinitionRepository meterRepo,
                           MeterReadingRepository readingRepo,
                           MeterReplacementRepository replacementRepo,
                           MaintenanceBaselineRepository baselineRepo,
                           MaintenanceReminderRepository reminderRepo,
                           WorkOrderRepository workOrderRepo,
                           EquipmentRepository equipmentRepo) {
        this.meterRepo = meterRepo;
        this.readingRepo = readingRepo;
        this.replacementRepo = replacementRepo;
        this.baselineRepo = baselineRepo;
        this.reminderRepo = reminderRepo;
        this.workOrderRepo = workOrderRepo;
        this.equipmentRepo = equipmentRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IngestResult ingestOne(Long equipmentId, String equipmentCode, String metric,
                                  String sourceSeq, BigDecimal rawValue, LocalDateTime readAt) {
        // ---- 入参校验（不落库） ----
        Equipment equipment = resolveEquipment(equipmentId, equipmentCode);
        if (equipment == null) {
            return IngestResult.rejected(equipmentCode, equipmentId, metric, sourceSeq, "设备不存在");
        }
        if (metric == null || metric.isBlank()) {
            return IngestResult.rejected(equipment.getCode(), equipment.getId(), null, sourceSeq, "缺少计量项标识");
        }
        metric = metric.trim();
        if (sourceSeq == null || sourceSeq.isBlank()) {
            return IngestResult.rejected(equipment.getCode(), equipment.getId(), metric, null, "缺少来源序号，无法去重");
        }
        sourceSeq = sourceSeq.trim();
        if (rawValue == null || rawValue.signum() < 0) {
            return IngestResult.rejected(equipment.getCode(), equipment.getId(), metric, sourceSeq, "读数缺失或为负数");
        }

        MeterDefinition meter = meterRepo.findByEquipmentIdAndMetric(equipment.getId(), metric).orElse(null);
        if (meter == null) {
            // 未配置计量项的设备保持兼容：读数被拒绝而不是报错，台账/手工工单行为不受影响
            return IngestResult.rejected(equipment.getCode(), equipment.getId(), metric, sourceSeq,
                    "设备未配置该计量项：" + metric);
        }
        if (!Boolean.TRUE.equals(meter.getEnabled())) {
            return IngestResult.rejected(equipment.getCode(), equipment.getId(), metric, sourceSeq, "计量项已停用");
        }

        // ---- 对计量项加行锁，串行化同一计量项的所有计算 ----
        meter = meterRepo.findByIdForUpdate(meter.getId()).orElseThrow();

        // 幂等去重：设备 + 计量项 + 来源序号
        MeterReading existing = readingRepo.findByMeterIdAndSourceSeq(meter.getId(), sourceSeq).orElse(null);
        if (existing != null) {
            MaintenanceReminder linkedReminder = reminderRepo
                    .findByMeterIdAndCycleIndex(meter.getId(), currentCycle(meter.getId()))
                    .orElse(null);
            return new IngestResult(equipment.getCode(), equipment.getId(), metric, sourceSeq,
                    "duplicate", "来源序号重复，已忽略",
                    existing.getId(), existing.getRawValue(), existing.getCumulativeValue(),
                    linkedReminder == null ? null : linkedReminder.getId(),
                    linkedReminder == null ? null : linkedReminder.getWorkOrderId());
        }

        // 当前代表与折算偏移（换表只改偏移，从不改写历史累计）
        MeterReplacement currentGen = replacementRepo.findTopByMeterIdOrderByIdDesc(meter.getId()).orElse(null);
        BigDecimal offset = currentGen == null ? BigDecimal.ZERO : currentGen.getOffsetValue();
        BigDecimal cumulative = rawValue.add(offset);

        // 单调递增：累计值不得小于上一条读数（迟到旧读数在此被挡下，不可能触发任何周期）
        MeterReading latest = readingRepo.findTopByMeterIdOrderByIdDesc(meter.getId()).orElse(null);
        if (latest != null && cumulative.compareTo(latest.getCumulativeValue()) < 0) {
            return IngestResult.rejected(equipment.getCode(), equipment.getId(), metric, sourceSeq,
                    "读数倒退：累计 " + cumulative + " 小于最新累计 " + latest.getCumulativeValue());
        }

        MeterReading reading = new MeterReading();
        reading.setEquipmentId(equipment.getId());
        reading.setMeterId(meter.getId());
        reading.setMetric(metric);
        reading.setSourceSeq(sourceSeq);
        reading.setRawValue(rawValue);
        reading.setCumulativeValue(cumulative);
        reading.setReplacementId(currentGen == null ? null : currentGen.getId());
        reading.setReadAt(readAt == null ? LocalDateTime.now() : readAt);
        // 行锁已保证同计量项上报串行，来源序号预查权威；唯一索引为并发兜底
        reading = readingRepo.save(reading);

        EvalOutcome outcome = evaluate(meter, reading);
        return new IngestResult(equipment.getCode(), equipment.getId(), metric, sourceSeq,
                "accepted", outcome.reason(),
                reading.getId(), rawValue, cumulative,
                outcome.reminderId(), outcome.workOrderId());
    }

    /** 读数入库后的保养判定：提前量提醒 → 越阈值自动开工单（同周期已有未完工单则不重复开）。 */
    private EvalOutcome evaluate(MeterDefinition meter, MeterReading reading) {
        MaintenanceBaseline baseline = baselineRepo.findTopByMeterIdOrderByIdDesc(meter.getId()).orElse(null);
        BigDecimal baseCumulative = baseline == null ? BigDecimal.ZERO : baseline.getCumulativeValue();
        int cycleIndex = (baseline == null ? 0 : baseline.getCycleIndex()) + 1;

        BigDecimal usage = reading.getCumulativeValue().subtract(baseCumulative);
        BigDecimal threshold = meter.getThreshold();
        BigDecimal leadPoint = threshold.subtract(
                meter.getLeadDistance() == null ? BigDecimal.ZERO : meter.getLeadDistance());

        // 基准之前的用量（迟到旧读数 / 保养后回传）一律不参与触发
        if (usage.signum() < 0) {
            return new EvalOutcome(null, null, "读数早于保养基准，已接收但不触发");
        }

        Long reminderId = null;
        Long workOrderId = null;

        MaintenanceReminder reminder = reminderRepo.findByMeterIdAndCycleIndex(meter.getId(), cycleIndex).orElse(null);

        if (usage.compareTo(threshold) >= 0) {
            // 越过阈值：先确保同周期有提醒，再决定是否自动开工单
            if (reminder == null) {
                reminder = newReminder(meter, reading, cycleIndex);
                reminder = reminderRepo.save(reminder);
            }
            reminderId = reminder.getId();

            WorkOrder openOrder = workOrderRepo
                    .findOpenMaintenanceForMeter(meter.getEquipmentId(), meter.getId(), OPEN_STATUSES)
                    .stream().findFirst().orElse(null);
            if (openOrder != null) {
                if (reminder.getWorkOrderId() == null) {
                    reminder.setWorkOrderId(openOrder.getId());
                    reminderRepo.save(reminder);
                }
                workOrderId = openOrder.getId();
                return new EvalOutcome(reminderId, workOrderId, "已越过阈值，当前周期已有未关闭保养工单，不重复开工单");
            }

            Equipment equip = equipmentRepo.findById(meter.getEquipmentId()).orElse(null);
            String equipName = equip == null ? ("设备#" + meter.getEquipmentId()) : equip.getName();
            WorkOrder wo = new WorkOrder();
            wo.setEquipmentId(meter.getEquipmentId());
            wo.setType("maintenance");
            wo.setPriority("high");
            wo.setTitle("[计量保养] " + equipName + " · " + meter.getName() + " 达到保养阈值");
            wo.setDescription(buildDescription(meter, reading, usage, baseCumulative));
            wo.setAssignee("");
            wo.setStatus("open");
            wo.setSourceType("metering");
            wo.setMeterId(meter.getId());
            wo.setMetric(meter.getMetric());
            wo.setTriggerReadingId(reading.getId());
            wo = workOrderRepo.save(wo);

            reminder.setWorkOrderId(wo.getId());
            reminder = reminderRepo.save(reminder);
            return new EvalOutcome(reminder.getId(), wo.getId(), "越过保养阈值，已自动创建保养工单");
        }

        if (usage.compareTo(leadPoint) >= 0) {
            if (reminder == null) {
                reminder = newReminder(meter, reading, cycleIndex);
                reminder = reminderRepo.save(reminder);
            }
            return new EvalOutcome(reminder.getId(), null, "进入提前量区间，已生成待确认提醒");
        }

        return new EvalOutcome(null, null, "已接收");
    }

    private MaintenanceReminder newReminder(MeterDefinition meter, MeterReading reading, int cycleIndex) {
        MaintenanceReminder r = new MaintenanceReminder();
        r.setEquipmentId(meter.getEquipmentId());
        r.setMeterId(meter.getId());
        r.setMetric(meter.getMetric());
        r.setCycleIndex(cycleIndex);
        r.setStatus("pending");
        r.setTriggerReadingId(reading.getId());
        r.setTriggerCumulative(reading.getCumulativeValue());
        return r;
    }

    private String buildDescription(MeterDefinition meter, MeterReading reading,
                                    BigDecimal usage, BigDecimal baseCumulative) {
        String unit = meter.getUnit() == null ? "" : meter.getUnit();
        return "来源：控制器计量读数自动触发\n"
                + "计量项：" + meter.getName() + "（" + meter.getMetric() + "）\n"
                + "触发读数ID：" + reading.getId() + "，来源序号：" + reading.getSourceSeq() + "\n"
                + "周期起始基准累计：" + baseCumulative + unit + "\n"
                + "当前累计：" + reading.getCumulativeValue() + unit + "\n"
                + "本周期用量：" + usage + unit + "，保养阈值：" + meter.getThreshold() + unit;
    }

    private int currentCycle(Long meterId) {
        MaintenanceBaseline b = baselineRepo.findTopByMeterIdOrderByIdDesc(meterId).orElse(null);
        return (b == null ? 0 : b.getCycleIndex()) + 1;
    }

    private Equipment resolveEquipment(Long equipmentId, String equipmentCode) {
        if (equipmentId != null) {
            return equipmentRepo.findById(equipmentId).orElse(null);
        }
        if (equipmentCode != null && !equipmentCode.isBlank()) {
            return equipmentRepo.findByCode(equipmentCode.trim()).orElse(null);
        }
        return null;
    }

    private record EvalOutcome(Long reminderId, Long workOrderId, String reason) {}
}
