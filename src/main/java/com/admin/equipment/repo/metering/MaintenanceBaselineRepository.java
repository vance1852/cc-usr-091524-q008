package com.admin.equipment.repo.metering;

import com.admin.equipment.model.metering.MaintenanceBaseline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaintenanceBaselineRepository extends JpaRepository<MaintenanceBaseline, Long> {

    /** 最近一次保养基准（周期判定的锚点）。 */
    Optional<MaintenanceBaseline> findTopByMeterIdOrderByIdDesc(Long meterId);
    List<MaintenanceBaseline> findByEquipmentIdOrderByIdDesc(Long equipmentId);
    boolean existsByWorkOrderIdAndMeterId(Long workOrderId, Long meterId);
    Optional<MaintenanceBaseline> findByWorkOrderIdAndMetric(Long workOrderId, String metric);
}
