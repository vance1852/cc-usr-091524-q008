package com.admin.equipment.web.metering;

import com.admin.equipment.model.metering.MeterDefinition;
import com.admin.equipment.service.metering.MeterDefinitionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 设备累计计量项、保养阈值与提前量配置。 */
@RestController
@RequestMapping("/api/metering/definitions")
public class MeterDefinitionController {

    private final MeterDefinitionService service;

    public MeterDefinitionController(MeterDefinitionService service) {
        this.service = service;
    }

    public record DefinitionRequest(Long equipmentId, String metric, String name, String unit,
                                    BigDecimal threshold, BigDecimal leadDistance, Boolean enabled) {}

    @GetMapping
    public List<MeterDefinition> list(@RequestParam Long equipmentId) {
        return service.listByEquipment(equipmentId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.getById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody DefinitionRequest req) {
        try {
            MeterDefinition d = service.create(req.equipmentId(), req.metric(), req.name(), req.unit(),
                    req.threshold(), req.leadDistance(), req.enabled());
            return ResponseEntity.status(HttpStatus.CREATED).body(d);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody DefinitionRequest req) {
        try {
            MeterDefinition d = service.update(id, req.name(), req.unit(),
                    req.threshold(), req.leadDistance(), req.enabled());
            return ResponseEntity.ok(d);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }
}
