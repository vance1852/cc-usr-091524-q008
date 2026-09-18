package com.admin.equipment.web.meter;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.model.meter.MeterReading;
import com.admin.equipment.model.meter.MeterReplacement;
import com.admin.equipment.service.meter.MeterService;
import com.admin.equipment.service.meter.MeterValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 运行计量驱动保养接口：
 * - 计量项配置（阈值/提前量）
 * - 控制器读数批量上报（逐条返回 accepted / duplicate / rejected 及原因）
 * - 授权换表登记、读数与换表流水查询
 * - 设备距各项保养剩余量及触发来源
 * - 提前提醒待确认列表与人工处理
 */
@RestController
@RequestMapping("/api/meters")
public class MeterController {

    private final MeterService service;

    public MeterController(MeterService service) {
        this.service = service;
    }

    public record BatchRequest(List<MeterService.ReadingInput> readings) {}

    public record DefinitionRequest(Long equipmentId, String metric, String name, String unit,
                                    java.math.BigDecimal threshold, java.math.BigDecimal lead,
                                    Boolean enabled) {}

    public record ReplacementRequest(java.math.BigDecimal oldFinalValue,
                                     java.math.BigDecimal newStartValue, String reason) {}

    public record ReminderActionRequest(String action) {}

    // ===== 计量项配置 =====

    @GetMapping("/definitions")
    public List<?> listDefinitions(@RequestParam(required = false) Long equipmentId) {
        return service.listDefinitions(equipmentId);
    }

    @PostMapping("/definitions")
    public ResponseEntity<?> saveDefinition(@RequestBody DefinitionRequest req) {
        if (req == null || req.equipmentId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "equipmentId 必填"));
        }
        MeterService.DefinitionInput in = new MeterService.DefinitionInput(
                req.equipmentId(), req.metric(), req.name(), req.unit(),
                req.threshold(), req.lead(), req.enabled());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveDefinition(in));
    }

    @DeleteMapping("/definitions/{id}")
    public ResponseEntity<?> deleteDefinition(@PathVariable Long id) {
        service.deleteDefinition(id);
        return ResponseEntity.noContent().build();
    }

    // ===== 批量读数上报 =====

    @PostMapping("/readings/batch")
    public ResponseEntity<?> batch(@RequestBody BatchRequest req) {
        if (req == null || req.readings() == null) {
            throw new MeterValidationException(422, "请求体需要 readings 数组");
        }
        List<MeterService.ReadingResult> results = service.ingestBatch(req.readings());
        // 逐条结果携带接纳/重复/拒绝语义，HTTP 统一 200，便于采集端按条目处理
        return ResponseEntity.ok(Map.of("total", results.size(),
                        "accepted", results.stream().filter(r -> MeterService.ACCEPTED.equals(r.status())).count(),
                        "duplicate", results.stream().filter(r -> MeterService.DUPLICATE.equals(r.status())).count(),
                        "rejected", results.stream().filter(r -> MeterService.REJECTED.equals(r.status())).count(),
                        "results", results));
    }

    @GetMapping("/equipment/{equipmentId}/metrics/{metric}/readings")
    public List<MeterReading> readings(@PathVariable Long equipmentId, @PathVariable String metric,
                                       @RequestParam(defaultValue = "100") int limit) {
        return service.listReadings(equipmentId, metric, Math.max(1, Math.min(limit, 1000)));
    }

    // ===== 授权换表 =====

    @PostMapping("/equipment/{equipmentId}/metrics/{metric}/replacements")
    public ResponseEntity<?> replacement(@PathVariable Long equipmentId, @PathVariable String metric,
                                         @RequestBody ReplacementRequest req, HttpServletRequest request) {
        if (req == null || req.oldFinalValue() == null || req.newStartValue() == null) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("detail", "旧表终值 oldFinalValue 与新表起点 newStartValue 必填"));
        }
        AppUser user = (AppUser) request.getAttribute("currentUser");
        String username = user == null ? "" : user.getUsername();
        MeterService.ReplacementInput in = new MeterService.ReplacementInput(
                req.oldFinalValue(), req.newStartValue(), req.reason());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.registerReplacement(equipmentId, metric, in, username));
    }

    @GetMapping("/equipment/{equipmentId}/metrics/{metric}/replacements")
    public List<MeterReplacement> replacements(@PathVariable Long equipmentId, @PathVariable String metric) {
        return service.listReplacements(equipmentId, metric);
    }

    // ===== 剩余量与触发来源 =====

    @GetMapping("/equipment/{equipmentId}/status")
    public MeterService.EquipmentMeterStatus equipmentStatus(@PathVariable Long equipmentId) {
        return service.equipmentStatus(equipmentId);
    }

    @GetMapping("/status")
    public List<MeterService.EquipmentMeterStatus> allStatus() {
        return service.allStatuses();
    }

    // ===== 待确认提醒 =====

    @GetMapping("/reminders")
    public List<?> reminders(@RequestParam(required = false) String status) {
        return service.listReminders(status);
    }

    @PostMapping("/reminders/{id}")
    public Object resolveReminder(@PathVariable Long id, @RequestBody ReminderActionRequest req) {
        String action = req == null ? "" : req.action();
        return service.resolveReminder(id, action);
    }
}
