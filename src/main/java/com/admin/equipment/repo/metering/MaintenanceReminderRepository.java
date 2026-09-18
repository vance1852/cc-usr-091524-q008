package com.admin.equipment.repo.metering;

import com.admin.equipment.model.metering.MaintenanceReminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaintenanceReminderRepository extends JpaRepository<MaintenanceReminder, Long> {

    /** 同周期去重：一个计量项一个周期只提醒一次。 */
    Optional<MaintenanceReminder> findByMeterIdAndCycleIndex(Long meterId, Integer cycleIndex);
    List<MaintenanceReminder> findByEquipmentIdOrderByIdDesc(Long equipmentId);
    List<MaintenanceReminder> findByStatusOrderByIdDesc(String status);
    List<MaintenanceReminder> findAllByOrderByIdDesc();
    List<MaintenanceReminder> findByMeterIdOrderByIdDesc(Long meterId);
}
