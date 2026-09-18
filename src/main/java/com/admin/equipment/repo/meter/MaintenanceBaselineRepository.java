package com.admin.equipment.repo.meter;

import com.admin.equipment.model.meter.MaintenanceBaseline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MaintenanceBaselineRepository extends JpaRepository<MaintenanceBaseline, Long> {

    Optional<MaintenanceBaseline> findTopByEquipmentIdAndMetricOrderByIdDesc(Long equipmentId,
                                                                             String metric);

    boolean existsByWorkOrderId(Long workOrderId);
}
