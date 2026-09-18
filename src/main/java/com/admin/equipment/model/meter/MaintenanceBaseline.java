package com.admin.equipment.model.meter;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 保养基准：计量驱动的 maintenance 工单完成时记录一条。
 * 同一 (equipmentId, metric) 最新一条的 baselineValue 即当前保养周期起点，
 * 历史累计值不高于该起点的迟到读数只入库、不再触发提醒或工单。
 */
@Entity
@Table(name = "maintenance_baselines",
        indexes = @Index(name = "idx_meter_baseline_equip_metric", columnList = "equipment_id,metric"))
public class MaintenanceBaseline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(nullable = false, length = 32)
    private String metric;

    // 完成保养的工单（一张工单至多产生一条基准）
    @Column(name = "work_order_id", nullable = false, unique = true)
    private Long workOrderId;

    // 基准历史累计值（工单完成时刻该计量项的最新历史累计值）
    @Column(name = "baseline_value", nullable = false, precision = 18, scale = 3)
    private BigDecimal baselineValue;

    // 完成时生效的阈值快照
    @Column(name = "threshold_value", precision = 18, scale = 3)
    private BigDecimal thresholdValue;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public BigDecimal getBaselineValue() { return baselineValue; }
    public void setBaselineValue(BigDecimal baselineValue) { this.baselineValue = baselineValue; }
    public BigDecimal getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(BigDecimal thresholdValue) { this.thresholdValue = thresholdValue; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
