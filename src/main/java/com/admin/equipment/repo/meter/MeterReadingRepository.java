package com.admin.equipment.repo.meter;

import com.admin.equipment.model.meter.MeterReading;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeterReadingRepository extends JpaRepository<MeterReading, Long> {

    Optional<MeterReading> findTopByEquipmentIdAndMetricOrderByIdDesc(Long equipmentId, String metric);

    List<MeterReading> findByEquipmentIdAndMetricOrderByIdAsc(Long equipmentId, String metric);

    boolean existsByEquipmentIdAndMetric(Long equipmentId, String metric);

    boolean existsByEquipmentIdAndMetricAndSourceSeq(Long equipmentId, String metric, String sourceSeq);

    Optional<MeterReading> findByEquipmentIdAndMetricAndSourceSeq(Long equipmentId, String metric, String sourceSeq);
}
