package com.admin.equipment.repo.metering;

import com.admin.equipment.model.metering.MeterDefinition;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MeterDefinitionRepository extends JpaRepository<MeterDefinition, Long> {

    List<MeterDefinition> findByEquipmentIdOrderByIdAsc(Long equipmentId);
    Optional<MeterDefinition> findByEquipmentIdAndMetric(Long equipmentId, String metric);
    boolean existsByEquipmentIdAndMetric(Long equipmentId, String metric);

    /** 行锁：同一计量项的上报、换表、工单完成全部串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from MeterDefinition d where d.id = :id")
    Optional<MeterDefinition> findByIdForUpdate(@Param("id") Long id);
}
