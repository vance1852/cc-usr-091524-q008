package com.admin.equipment.repo;

import com.admin.equipment.model.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {
    List<WorkOrder> findAllByOrderByIdDesc();
    List<WorkOrder> findByEquipmentIdOrderByIdDesc(Long equipmentId);
    List<WorkOrder> findByStatusOrderByIdDesc(String status);
    long countByStatus(String status);

    /**
     * 同周期是否已有未关闭保养工单：命中该计量项专属工单（meterId 匹配）
     * 或覆盖整台设备的通用保养工单（手工/巡检转单，meterId 为空）。
     * 其他计量项的工单不阻止本计量项开工单。
     */
    @Query("""
            select w from WorkOrder w
            where w.equipmentId = :equipmentId
              and w.type = 'maintenance'
              and w.status in :statuses
              and (w.meterId = :meterId or w.meterId is null)
            order by w.id desc""")
    List<WorkOrder> findOpenMaintenanceForMeter(@Param("equipmentId") Long equipmentId,
                                                @Param("meterId") Long meterId,
                                                @Param("statuses") Collection<String> statuses);
}
