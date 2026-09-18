package com.admin.equipment.repo.meter;

import com.admin.equipment.model.meter.MeterDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MeterDefinitionRepository extends JpaRepository<MeterDefinition, Long> {

    List<MeterDefinition> findByEquipmentIdOrderByIdAsc(Long equipmentId);

    Optional<MeterDefinition> findByEquipmentIdAndMetric(Long equipmentId, String metric);

    boolean existsByEquipmentIdAndMetric(Long equipmentId, String metric);

    long countByEnabledTrue();

    /**
     * 按设备+计量项加行锁，必须作为事务内第一条读语句，
     * 保证 InnoDB 读视图在取锁后建立，后续普通查询能看到最新已提交读数。
     */
    @Query(value = "SELECT * FROM meter_definitions WHERE equipment_id = :equipmentId AND metric = :metric FOR UPDATE",
            nativeQuery = true)
    Optional<MeterDefinition> lockByEquipmentAndMetric(@Param("equipmentId") Long equipmentId,
                                                       @Param("metric") String metric);
}
