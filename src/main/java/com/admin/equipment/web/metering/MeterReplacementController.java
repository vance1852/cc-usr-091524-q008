package com.admin.equipment.web.metering;

import com.admin.equipment.model.metering.MeterReplacement;
import com.admin.equipment.service.metering.MeterReplacementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** 授权换表登记：旧表终值 + 新表起点，历史累计不改写。 */
@RestController
@RequestMapping("/api/metering/replacements")
public class MeterReplacementController {

    private final MeterReplacementService service;

    public MeterReplacementController(MeterReplacementService service) {
        this.service = service;
    }

    public record ReplacementRequest(Long meterId, BigDecimal oldFinalValue, BigDecimal newStartValue,
                                     String reason, String operator, String replacedAt) {}

    @GetMapping
    public List<MeterReplacement> list(@RequestParam(required = false) Long equipmentId,
                                       @RequestParam(required = false) Long meterId) {
        if (meterId != null) return service.listByMeter(meterId);
        if (equipmentId != null) return service.listByEquipment(equipmentId);
        return List.of();
    }

    @PostMapping
    public ResponseEntity<?> register(@RequestBody ReplacementRequest req) {
        if (req.meterId() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "计量项ID必填"));
        }
        LocalDateTime replacedAt = null;
        if (req.replacedAt() != null && !req.replacedAt().isBlank()) {
            try {
                replacedAt = LocalDateTime.parse(req.replacedAt().trim());
            } catch (DateTimeParseException e) {
                return ResponseEntity.unprocessableEntity()
                        .body(Map.of("detail", "replacedAt 时间格式无法解析（应为 ISO-8601）"));
            }
        }
        try {
            MeterReplacement r = service.register(req.meterId(), req.oldFinalValue(), req.newStartValue(),
                    req.reason(), req.operator(), replacedAt);
            return ResponseEntity.status(HttpStatus.CREATED).body(r);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }
}
