package com.admin.equipment.service.metering;

import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.metering.MaintenanceBaseline;
import com.admin.equipment.model.metering.MaintenanceReminder;
import com.admin.equipment.model.metering.MeterDefinition;
import com.admin.equipment.model.metering.MeterReading;
import com.admin.equipment.repo.metering.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 保养工单完成时落保养基准，重启（done 改回未完成）时撤回基准，
 * 保证上报、换表、工单关闭与重启后的周期计算始终一致。
 */
@Service
public class MaintenanceCompletionService {

    private final MeterDefinitionRepository meterRepo;
    private final MeterReadingRepository readingRepo;
    private final MaintenanceBaselineRepository baselineRepo;
    private final MaintenanceReminderRepository reminderRepo;

    public MaintenanceCompletionService(MeterDefinitionRepository meterRepo,
                                        MeterReadingRepository readingRepo,
                                        MaintenanceBaselineRepository baselineRepo,
                                        MaintenanceReminderRepository reminderRepo) {
        this.meterRepo = meterRepo;
        this.readingRepo = readingRepo;
        this.baselineRepo = baselineRepo;
        this.reminderRepo = reminderRepo;
    }

    /** 工单完成：为关联计量项（手工保养工单则为设备全部计量项）登记本周期基准。 */
    @Transactional
    public void onWorkOrderDone(WorkOrder wo) {
        if (!"maintenance".equals(wo.getType())) return;

        List<MeterDefinition> meters;
        if (wo.getMeterId() != null) {
            meters = meterRepo.findById(wo.getMeterId()).map(List::of).orElse(List.of());
        } else {
            meters = meterRepo.findByEquipmentIdOrderByIdAsc(wo.getEquipmentId());
        }
        for (MeterDefinition meter : meters) {
            recordBaseline(wo, meter);
        }
    }

    private void recordBaseline(WorkOrder wo, MeterDefinition meter) {
        // 行锁与上报/换表互斥，先加锁再查重（并发关闭安全）
        meter = meterRepo.findByIdForUpdate(meter.getId()).orElseThrow();

        // 幂等：同一工单同一计量项只允许一条基准（并发关闭/重复提交安全）
        if (baselineRepo.existsByWorkOrderIdAndMeterId(wo.getId(), meter.getId())) return;

        MeterReading latest = readingRepo.findTopByMeterIdOrderByIdDesc(meter.getId()).orElse(null);
        if (latest == null) return; // 从无读数的计量项不立基准

        MaintenanceBaseline last = baselineRepo.findTopByMeterIdOrderByIdDesc(meter.getId()).orElse(null);
        int cycleIndex = (last == null ? 1 : last.getCycleIndex() + 1);

        MaintenanceBaseline b = new MaintenanceBaseline();
        b.setEquipmentId(meter.getEquipmentId());
        b.setMeterId(meter.getId());
        b.setMetric(meter.getMetric());
        b.setWorkOrderId(wo.getId());
        b.setCycleIndex(cycleIndex);
        b.setCumulativeValue(latest.getCumulativeValue());
        baselineRepo.save(b);

        // 本周期及更早的待确认提醒随周期结束失效
        for (MaintenanceReminder r : reminderRepo.findByMeterIdOrderByIdDesc(meter.getId())) {
            if (r.getCycleIndex() <= cycleIndex && !"obsolete".equals(r.getStatus())) {
                r.setStatus("obsolete");
                reminderRepo.save(r);
            }
        }
    }

    /** 工单由 done 改回 open/in_progress：撤回该工单落下的基准并恢复当周期提醒。 */
    @Transactional
    public void onWorkOrderReopened(WorkOrder wo) {
        if (!"maintenance".equals(wo.getType())) return;

        List<MaintenanceBaseline> baselines;
        if (wo.getMeterId() != null) {
            MeterDefinition meter = meterRepo.findById(wo.getMeterId()).orElse(null);
            if (meter == null) return;
            meter = meterRepo.findByIdForUpdate(meter.getId()).orElseThrow();
            baselines = baselineRepo.findByWorkOrderIdAndMetric(wo.getId(), meter.getMetric())
                    .map(List::of).orElse(List.of());
        } else {
            baselines = baselineRepo.findByEquipmentIdOrderByIdDesc(wo.getEquipmentId()).stream()
                    .filter(b -> wo.getId().equals(b.getWorkOrderId()))
                    .toList();
        }
        for (MaintenanceBaseline b : baselines) {
            int cycleIndex = b.getCycleIndex();
            baselineRepo.delete(b);
            // 恢复该周期被置为失效的提醒
            MaintenanceReminder r = reminderRepo.findByMeterIdAndCycleIndex(b.getMeterId(), cycleIndex).orElse(null);
            if (r != null && "obsolete".equals(r.getStatus())) {
                r.setStatus("pending");
                r.setConfirmedAt(null);
                reminderRepo.save(r);
            }
        }
    }

    /** 供状态展示复用：用量、周期与剩余量计算。 */
    public CycleView viewCycle(MeterDefinition meter) {
        MeterReading latest = readingRepo.findTopByMeterIdOrderByIdDesc(meter.getId()).orElse(null);
        MaintenanceBaseline baseline = baselineRepo.findTopByMeterIdOrderByIdDesc(meter.getId()).orElse(null);
        BigDecimal cumulative = latest == null ? null : latest.getCumulativeValue();
        BigDecimal base = baseline == null ? BigDecimal.ZERO : baseline.getCumulativeValue();
        int cycleIndex = (baseline == null ? 0 : baseline.getCycleIndex()) + 1;
        BigDecimal usage = cumulative == null ? BigDecimal.ZERO : cumulative.subtract(base);
        if (usage.signum() < 0) usage = BigDecimal.ZERO;
        BigDecimal remaining = meter.getThreshold().subtract(usage);
        return new CycleView(latest, baseline, cycleIndex, cumulative, base, usage, remaining);
    }

    public record CycleView(MeterReading latest, MaintenanceBaseline baseline, int cycleIndex,
                            BigDecimal cumulative, BigDecimal baselineValue,
                            BigDecimal cycleUsage, BigDecimal remaining) {}
}
