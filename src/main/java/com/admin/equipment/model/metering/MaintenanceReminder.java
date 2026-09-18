package com.admin.equipment.model.metering;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 保养提醒：本周期用量进入提前量区间时生成一条待确认提醒，
 * 同一计量项同一周期至多一条；越阈值自动开工单后回填 workOrderId，
 * 周期结束（保养完成）后旧周期提醒置为 obsolete。
 */
@Entity
@Table(name = "maintenance_reminders",
        indexes = {
                @Index(name = "idx_reminder_meter_cycle", columnList = "meter_id,cycle_index"),
                @Index(name = "idx_reminder_status", columnList = "status"),
                @Index(name = "idx_reminder_equipment", columnList = "equipment_id")
        })
public class MaintenanceReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "meter_id", nullable = false)
    private Long meterId;

    @Column(nullable = false, length = 48)
    private String metric;

    @Column(name = "cycle_index", nullable = false)
    private Integer cycleIndex;

    // pending 待确认 / confirmed 已确认 / obsolete 已随周期结束失效
    @Column(nullable = false, length = 16)
    private String status = "pending";

    @Column(name = "trigger_reading_id")
    private Long triggerReadingId;

    @Column(name = "trigger_cumulative", precision = 20, scale = 3)
    private BigDecimal triggerCumulative;

    @Column(name = "work_order_id")
    private Long workOrderId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public Long getMeterId() { return meterId; }
    public void setMeterId(Long meterId) { this.meterId = meterId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public Integer getCycleIndex() { return cycleIndex; }
    public void setCycleIndex(Integer cycleIndex) { this.cycleIndex = cycleIndex; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTriggerReadingId() { return triggerReadingId; }
    public void setTriggerReadingId(Long triggerReadingId) { this.triggerReadingId = triggerReadingId; }
    public BigDecimal getTriggerCumulative() { return triggerCumulative; }
    public void setTriggerCumulative(BigDecimal triggerCumulative) { this.triggerCumulative = triggerCumulative; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
}
