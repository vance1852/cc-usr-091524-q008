package com.admin.equipment.web.metering;

import com.admin.equipment.model.metering.MaintenanceReminder;
import com.admin.equipment.service.metering.MaintenanceReminderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 保养提醒查询与确认。 */
@RestController
@RequestMapping("/api/maintenance/reminders")
public class MaintenanceReminderController {

    private final MaintenanceReminderService service;

    public MaintenanceReminderController(MaintenanceReminderService service) {
        this.service = service;
    }

    @GetMapping
    public List<MaintenanceReminder> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long equipmentId) {
        if (equipmentId != null) return service.listByEquipment(equipmentId);
        return service.listAll(status);
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.confirm(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }
}
