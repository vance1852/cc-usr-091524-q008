package com.admin.equipment.web;

import com.admin.equipment.model.meter.MaintenanceReminder;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.meter.MaintenanceReminderRepository;
import com.admin.equipment.repo.meter.MeterDefinitionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final EquipmentRepository equipmentRepo;
    private final WorkOrderRepository workOrderRepo;
    private final MeterDefinitionRepository meterDefRepo;
    private final MaintenanceReminderRepository reminderRepo;

    public DashboardController(EquipmentRepository equipmentRepo, WorkOrderRepository workOrderRepo,
                               MeterDefinitionRepository meterDefRepo,
                               MaintenanceReminderRepository reminderRepo) {
        this.equipmentRepo = equipmentRepo;
        this.workOrderRepo = workOrderRepo;
        this.meterDefRepo = meterDefRepo;
        this.reminderRepo = reminderRepo;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("equipment_total", equipmentRepo.count());
        m.put("equipment_fault", equipmentRepo.countByStatus("fault"));
        m.put("equipment_maintenance", equipmentRepo.countByStatus("maintenance"));
        m.put("work_order_total", workOrderRepo.count());
        m.put("work_order_open", workOrderRepo.countByStatus("open"));
        m.put("work_order_in_progress", workOrderRepo.countByStatus("in_progress"));
        m.put("meter_metric_count", meterDefRepo.count());
        m.put("meter_metric_enabled", meterDefRepo.countByEnabledTrue());
        m.put("meter_reminder_pending", reminderRepo.countByStatus(MaintenanceReminder.PENDING));
        return m;
    }
}
