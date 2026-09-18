package com.admin.equipment.web.metering;

import com.admin.equipment.model.metering.MeterReading;
import com.admin.equipment.service.metering.MeterReadingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 控制器读数批量上报：逐条返回 accepted / duplicate / rejected 及原因。 */
@RestController
@RequestMapping("/api/metering/readings")
public class MeterReadingController {

    private final MeterReadingService service;

    public MeterReadingController(MeterReadingService service) {
        this.service = service;
    }

    public record BatchRequest(List<MeterReadingService.ReadingItem> readings) {}

    @PostMapping("/batch")
    public Map<String, Object> batch(@RequestBody BatchRequest req) {
        MeterReadingService.BatchResult result = service.ingestBatch(req == null ? null : req.readings());
        return Map.of(
                "total", result.total(),
                "accepted", result.accepted(),
                "duplicate", result.duplicate(),
                "rejected", result.rejected(),
                "results", result.results());
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Long equipmentId,
                                  @RequestParam(required = false) Long meterId) {
        if (meterId != null) {
            return ResponseEntity.ok(service.listByMeter(meterId));
        }
        if (equipmentId != null) {
            return ResponseEntity.ok(service.listByEquipment(equipmentId));
        }
        return ResponseEntity.badRequest().body(Map.of("detail", "请提供 equipmentId 或 meterId"));
    }
}
