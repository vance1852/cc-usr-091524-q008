package com.admin.equipment.repo.metering;

import com.admin.equipment.model.metering.MeterReading;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeterReadingRepository extends JpaRepository<MeterReading, Long> {

    Optional<MeterReading> findByMeterIdAndSourceSeq(Long meterId, String sourceSeq);
    boolean existsByMeterIdAndSourceSeq(Long meterId, String sourceSeq);

    /** 累计值单调递增，id 最大的即最新读数。 */
    Optional<MeterReading> findTopByMeterIdOrderByIdDesc(Long meterId);

    List<MeterReading> findByMeterIdOrderByIdDesc(Long meterId);
    List<MeterReading> findByEquipmentIdOrderByIdDesc(Long equipmentId);
}
