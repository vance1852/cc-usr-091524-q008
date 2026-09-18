package com.admin.equipment.repo;

import com.admin.equipment.model.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {
    List<WorkOrder> findAllByOrderByIdDesc();
    List<WorkOrder> findByEquipmentIdOrderByIdDesc(Long equipmentId);
    List<WorkOrder> findByStatusOrderByIdDesc(String status);
    long countByStatus(String status);

    // 计量驱动保养：查设备某类型、状态集合内的工单
    List<WorkOrder> findByEquipmentIdAndTypeAndStatusInOrderByIdDesc(
            Long equipmentId, String type, Collection<String> statuses);
}
