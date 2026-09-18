package com.admin.equipment.repo.meter;

import com.admin.equipment.model.meter.MaintenanceReminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaintenanceReminderRepository extends JpaRepository<MaintenanceReminder, Long> {

    List<MaintenanceReminder> findByEquipmentIdAndMetricAndStatusInOrderByIdDesc(
            Long equipmentId, String metric, List<String> statuses);

    List<MaintenanceReminder> findByStatusOrderByIdDesc(String status);

    List<MaintenanceReminder> findAllByOrderByIdDesc();

    Optional<MaintenanceReminder> findFirstByEquipmentIdAndMetricAndStatusOrderByIdDesc(
            Long equipmentId, String metric, String status);

    long countByStatus(String status);
}
