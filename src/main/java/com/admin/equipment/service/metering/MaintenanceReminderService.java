package com.admin.equipment.service.metering;

import com.admin.equipment.model.metering.MaintenanceReminder;
import com.admin.equipment.repo.metering.MaintenanceReminderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MaintenanceReminderService {

    private final MaintenanceReminderRepository repo;

    public MaintenanceReminderService(MaintenanceReminderRepository repo) {
        this.repo = repo;
    }

    public List<MaintenanceReminder> listAll(String status) {
        if (status != null && !status.isBlank()) {
            return repo.findByStatusOrderByIdDesc(status.trim());
        }
        return repo.findAllByOrderByIdDesc();
    }

    public List<MaintenanceReminder> listByEquipment(Long equipmentId) {
        return repo.findByEquipmentIdOrderByIdDesc(equipmentId);
    }

    /** 工程师确认提醒（仅待确认提醒可确认；已失效提醒不可确认）。 */
    @Transactional
    public MaintenanceReminder confirm(Long id) {
        MaintenanceReminder r = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("提醒不存在"));
        if ("obsolete".equals(r.getStatus())) {
            throw new IllegalArgumentException("该提醒所属周期已结束，不能确认");
        }
        r.setStatus("confirmed");
        r.setConfirmedAt(LocalDateTime.now());
        return repo.save(r);
    }
}
