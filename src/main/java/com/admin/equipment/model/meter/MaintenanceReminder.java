package com.admin.equipment.model.meter;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 保养提前提醒：周期内累计用量进入提前窗口（threshold - lead）时生成一张“待确认”提醒。
 * 同一周期（由 cycleStartValue 标识）同一计量项至多保留一张 pending 提醒；
 * 越过阈值自动开工单后转为 converted，人工确认后为 confirmed，保养工单完成后未关联的为 dismissed。
 */
@Entity
@Table(name = "maintenance_reminders",
        indexes = @Index(name = "idx_meter_reminder_equip_metric", columnList = "equipment_id,metric"))
public class MaintenanceReminder {

    // pending 待确认 / confirmed 已确认 / converted 已转工单 / dismissed 已关闭
    public static final String PENDING = "pending";
    public static final String CONFIRMED = "confirmed";
    public static final String CONVERTED = "converted";
    public static final String DISMISSED = "dismissed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(nullable = false, length = 32)
    private String metric;

    // 所属保养周期的起点（最近基准值，首个周期为 0）
    @Column(name = "cycle_start_value", nullable = false, precision = 18, scale = 3)
    private BigDecimal cycleStartValue;

    // 触发提醒时的历史累计值
    @Column(name = "trigger_value", nullable = false, precision = 18, scale = 3)
    private BigDecimal triggerValue;

    @Column(name = "threshold_value", nullable = false, precision = 18, scale = 3)
    private BigDecimal thresholdValue;

    @Column(name = "lead_value", nullable = false, precision = 18, scale = 3)
    private BigDecimal leadValue;

    @Column(nullable = false, length = 16)
    private String status = PENDING;

    // 转为工单后关联的工单
    @Column(name = "work_order_id")
    private Long workOrderId;

    // 触发来源描述，如 控制器读数 AIR2-CTRL-001 / 换表登记
    @Column(name = "trigger_source", length = 256)
    private String triggerSource = "";

    @Column(length = 512)
    private String message = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public BigDecimal getCycleStartValue() { return cycleStartValue; }
    public void setCycleStartValue(BigDecimal cycleStartValue) { this.cycleStartValue = cycleStartValue; }
    public BigDecimal getTriggerValue() { return triggerValue; }
    public void setTriggerValue(BigDecimal triggerValue) { this.triggerValue = triggerValue; }
    public BigDecimal getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(BigDecimal thresholdValue) { this.thresholdValue = thresholdValue; }
    public BigDecimal getLeadValue() { return leadValue; }
    public void setLeadValue(BigDecimal leadValue) { this.leadValue = leadValue; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource == null ? "" : triggerSource; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message == null ? "" : message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getHandledAt() { return handledAt; }
    public void setHandledAt(LocalDateTime handledAt) { this.handledAt = handledAt; }
}
