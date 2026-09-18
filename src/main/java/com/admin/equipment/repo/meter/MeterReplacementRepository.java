package com.admin.equipment.repo.meter;

import com.admin.equipment.model.meter.MeterReplacement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeterReplacementRepository extends JpaRepository<MeterReplacement, Long> {

    Optional<MeterReplacement> findTopByEquipmentIdAndMetricOrderByIdDesc(Long equipmentId, String metric);

    List<MeterReplacement> findByEquipmentIdAndMetricOrderByIdAsc(Long equipmentId, String metric);
}
