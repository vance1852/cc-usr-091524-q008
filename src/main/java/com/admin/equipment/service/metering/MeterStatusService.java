package com.admin.equipment.service.metering;

import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.metering.MaintenanceReminder;
import com.admin.equipment.model.metering.MeterDefinition;
import com.admin.equipment.model.metering.MeterReading;
import com.admin.equipment.model.metering.MeterReplacement;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.metering.MaintenanceReminderRepository;
import com.admin.equipment.repo.metering.MeterDefinitionRepository;
import com.admin.equipment.repo.metering.MeterReplacementRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 展示设备距各项保养的剩余量、状态与触发来源；只读计算，
 * 逻辑与上报时的落库判定完全一致（以最近保养基准为周期锚点）。
 */
@Service
public class MeterStatusService {

    private static final List<String> OPEN_STATUSES = List.of("open", "in_progress");

    private final MeterDefinitionRepository meterRepo;
    private final MaintenanceReminderRepository reminderRepo;
    private final MeterReplacementRepository replacementRepo;
    private final WorkOrderRepository workOrderRepo;
    private final MaintenanceCompletionService completionService;

    public MeterStatusService(MeterDefinitionRepository meterRepo,
                              MaintenanceReminderRepository reminderRepo,
                              MeterReplacementRepository replacementRepo,
                              WorkOrderRepository workOrderRepo,
                              MaintenanceCompletionService completionService) {
        this.meterRepo = meterRepo;
        this.reminderRepo = reminderRepo;
        this.replacementRepo = replacementRepo;
        this.workOrderRepo = workOrderRepo;
        this.completionService = completionService;
    }

    public List<Map<String, Object>> statusByEquipment(Long equipmentId, Boolean includeDisabled) {
        List<MeterDefinition> defs = meterRepo.findByEquipmentIdOrderByIdAsc(equipmentId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MeterDefinition d : defs) {
            if (!Boolean.TRUE.equals(includeDisabled) && !Boolean.TRUE.equals(d.getEnabled())) continue;
            rows.add(buildRow(d));
        }
        return rows;
    }

    public Map<String, Object> buildRow(MeterDefinition d) {
        MaintenanceCompletionService.CycleView v = completionService.viewCycle(d);
        MaintenanceReminder reminder = reminderRepo
                .findByMeterIdAndCycleIndex(d.getId(), v.cycleIndex()).orElse(null);
        WorkOrder openOrder = workOrderRepo
                .findOpenMaintenanceForMeter(d.getEquipmentId(), d.getId(), OPEN_STATUSES)
                .stream().findFirst().orElse(null);
        MeterReplacement lastReplacement = replacementRepo.findTopByMeterIdOrderByIdDesc(d.getId()).orElse(null);

        String state;
        if (openOrder != null) {
            state = "work_order_open";
        } else if (v.remaining().compareTo(BigDecimal.ZERO) <= 0) {
            state = "overdue";
        } else if (inLead(d, v)) {
            state = "due_soon";
        } else {
            state = "normal";
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("meterId", d.getId());
        row.put("equipmentId", d.getEquipmentId());
        row.put("metric", d.getMetric());
        row.put("meterName", d.getName());
        row.put("unit", d.getUnit());
        row.put("enabled", d.getEnabled());
        row.put("threshold", d.getThreshold());
        row.put("leadDistance", d.getLeadDistance());
        row.put("cycleIndex", v.cycleIndex());
        row.put("cumulativeValue", v.cumulative());
        row.put("cycleUsage", v.cycleUsage());
        row.put("remaining", v.remaining());
        row.put("state", state);
        row.put("latestReading", v.latest() == null ? null : readingRef(v.latest()));
        row.put("baseline", v.baseline() == null ? null : Map.of(
                "id", v.baseline().getId(),
                "workOrderId", v.baseline().getWorkOrderId(),
                "cumulativeValue", v.baseline().getCumulativeValue()));
        row.put("reminder", reminder == null ? null : Map.of(
                "id", reminder.getId(),
                "status", reminder.getStatus(),
                "triggerReadingId", reminder.getTriggerReadingId() == null ? "" : reminder.getTriggerReadingId(),
                "triggerCumulative", reminder.getTriggerCumulative() == null ? "" : reminder.getTriggerCumulative(),
                "workOrderId", reminder.getWorkOrderId() == null ? "" : reminder.getWorkOrderId()));
        row.put("openWorkOrder", openOrder == null ? null : Map.of(
                "id", openOrder.getId(),
                "title", openOrder.getTitle(),
                "status", openOrder.getStatus(),
                "sourceType", openOrder.getSourceType() == null ? "manual" : openOrder.getSourceType(),
                "triggerReadingId", openOrder.getTriggerReadingId() == null ? "" : openOrder.getTriggerReadingId()));
        row.put("lastReplacement", lastReplacement == null ? null : Map.of(
                "id", lastReplacement.getId(),
                "oldFinalValue", lastReplacement.getOldFinalValue(),
                "newStartValue", lastReplacement.getNewStartValue(),
                "offsetValue", lastReplacement.getOffsetValue(),
                "replacedAt", lastReplacement.getReplacedAt() == null ? "" : lastReplacement.getReplacedAt()));
        return row;
    }

    private boolean inLead(MeterDefinition d, MaintenanceCompletionService.CycleView v) {
        BigDecimal lead = d.getLeadDistance() == null ? BigDecimal.ZERO : d.getLeadDistance();
        if (lead.signum() <= 0 || v.latest() == null) return false;
        return v.remaining().compareTo(lead) <= 0 && v.cycleUsage().compareTo(d.getThreshold()) < 0;
    }

    private Map<String, Object> readingRef(MeterReading r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("sourceSeq", r.getSourceSeq());
        m.put("rawValue", r.getRawValue());
        m.put("cumulativeValue", r.getCumulativeValue());
        m.put("readAt", r.getReadAt() == null ? "" : r.getReadAt());
        m.put("replacementId", r.getReplacementId() == null ? "" : r.getReplacementId());
        return m;
    }
}
