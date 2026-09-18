package com.admin.equipment.repo.metering;

import com.admin.equipment.model.metering.MeterReplacement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeterReplacementRepository extends JpaRepository<MeterReplacement, Long> {

    Optional<MeterReplacement> findTopByMeterIdOrderByIdDesc(Long meterId);
    List<MeterReplacement> findByMeterIdOrderByIdAsc(Long meterId);
    List<MeterReplacement> findByEquipmentIdOrderByIdDesc(Long equipmentId);
}
