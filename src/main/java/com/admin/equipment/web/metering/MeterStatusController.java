package com.admin.equipment.web.metering;

import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.service.metering.MeterStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 展示设备距各项保养的剩余量、状态及触发来源。 */
@RestController
@RequestMapping("/api/metering/status")
public class MeterStatusController {

    private final MeterStatusService service;
    private final EquipmentRepository equipmentRepo;

    public MeterStatusController(MeterStatusService service, EquipmentRepository equipmentRepo) {
        this.service = service;
        this.equipmentRepo = equipmentRepo;
    }

    @GetMapping
    public ResponseEntity<?> status(@RequestParam Long equipmentId,
                                    @RequestParam(required = false) Boolean includeDisabled) {
        if (!equipmentRepo.existsById(equipmentId)) {
            return ResponseEntity.status(404).body(Map.of("detail", "设备不存在"));
        }
        List<Map<String, Object>> rows = service.statusByEquipment(equipmentId, includeDisabled);
        return ResponseEntity.ok(Map.of(
                "equipmentId", equipmentId,
                // 未配置计量项的种子设备返回空列表，台账与手工工单行为不受影响
                "meters", rows));
    }
}
