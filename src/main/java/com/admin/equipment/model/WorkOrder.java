package com.admin.equipment.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "work_orders")
public class WorkOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(nullable = false, length = 128)
    private String title;

    // 工单类型：inspection 巡检 / repair 维修 / maintenance 保养
    @Column(length = 16)
    private String type = "inspection";

    // 优先级：low / medium / high / urgent
    @Column(length = 16)
    private String priority = "medium";

    // 状态：open 待处理 / in_progress 处理中 / done 已完成
    @Column(length = 16)
    private String status = "open";

    @Column(length = 512)
    private String description = "";

    @Column(name = "assignee", length = 64)
    private String assignee = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    // 来源：manual 手工创建 / inspection 巡检转单 / metering 计量越阈值自动创建
    @Column(name = "source_type", length = 16)
    private String sourceType = "manual";

    // 计量自动开工单时的溯源信息（手工/巡检工单为 null）
    @Column(name = "meter_id")
    private Long meterId;

    @Column(length = 48)
    private String metric;

    @Column(name = "trigger_reading_id")
    private Long triggerReadingId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getMeterId() { return meterId; }
    public void setMeterId(Long meterId) { this.meterId = meterId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public Long getTriggerReadingId() { return triggerReadingId; }
    public void setTriggerReadingId(Long triggerReadingId) { this.triggerReadingId = triggerReadingId; }
}
