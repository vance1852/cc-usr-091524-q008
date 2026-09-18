package com.admin.equipment.model.metering;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 保养基准：保养工单完成时，按当时该计量项的全生命周期累计值落一条基准。
 * 下一周期的用量 = 当前累计 - 最近一次基准累计；迟到的旧读数累计不大于基准，永不触发下一周期。
 */
@Entity
@Table(name = "maintenance_baselines",
        uniqueConstraints = @UniqueConstraint(name = "uk_baseline_work_order_meter",
                columnNames = {"work_order_id", "meter_id"}),
        indexes = {
                @Index(name = "idx_baseline_meter", columnList = "meter_id"),
                @Index(name = "idx_baseline_equipment", columnList = "equipment_id"),
                @Index(name = "idx_baseline_work_order", columnList = "work_order_id")
        })
public class MaintenanceBaseline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "meter_id", nullable = false)
    private Long meterId;

    @Column(nullable = false, length = 48)
    private String metric;

    @Column(name = "work_order_id", nullable = false)
    private Long workOrderId;

    // 周期序号：首周期为 1，每完成一次保养递增
    @Column(name = "cycle_index", nullable = false)
    private Integer cycleIndex;

    // 完成保养时的全生命周期累计值
    @Column(name = "cumulative_value", nullable = false, precision = 20, scale = 3)
    private BigDecimal cumulativeValue;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public Long getMeterId() { return meterId; }
    public void setMeterId(Long meterId) { this.meterId = meterId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public Integer getCycleIndex() { return cycleIndex; }
    public void setCycleIndex(Integer cycleIndex) { this.cycleIndex = cycleIndex; }
    public BigDecimal getCumulativeValue() { return cumulativeValue; }
    public void setCumulativeValue(BigDecimal cumulativeValue) { this.cumulativeValue = cumulativeValue; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
