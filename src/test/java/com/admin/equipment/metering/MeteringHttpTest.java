package com.admin.equipment.metering;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.repo.EquipmentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MeteringHttpTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EquipmentRepository equipmentRepo;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String login() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("access_token").asText();
    }

    private Equipment equipment() {
        Equipment e = new Equipment();
        e.setCode("HTTP-" + SEQ.incrementAndGet() + "-" + System.nanoTime());
        e.setName("HTTP测试空压机");
        e.setType("pump");
        e.setStatus("normal");
        return equipmentRepo.save(e);
    }

    @Test
    void batchStatusAndCloseFlowOverHttp() throws Exception {
        String token = login();
        Equipment e = equipment();

        // 无凭证 → 401
        mvc.perform(get("/api/metering/status").param("equipmentId", e.getId().toString()))
                .andExpect(status().isUnauthorized());

        // 配置计量项：阈值 500、提前量 50
        String defBody = json.writeValueAsString(Map.of(
                "equipmentId", e.getId(), "metric", "run_hours", "name", "运行小时",
                "unit", "h", "threshold", new BigDecimal("500"), "leadDistance", new BigDecimal("50")));
        mvc.perform(post("/api/metering/definitions").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(defBody))
                .andExpect(status().isCreated());

        // 未配置计量项的设备返回空 meters
        Equipment bare = equipment();
        MvcResult bareRes = mvc.perform(get("/api/metering/status")
                        .param("equipmentId", bare.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(bareRes.getResponse().getContentAsString()).get("meters").size()).isZero();

        // 批量上报：接纳 + 越阈值自动开工单
        String batch = json.writeValueAsString(Map.of("readings", List.of(
                Map.of("equipmentId", e.getId(), "metric", "run_hours", "sourceSeq", "h-1", "value", 460),
                Map.of("equipmentId", e.getId(), "metric", "run_hours", "sourceSeq", "h-1", "value", 460),
                Map.of("equipmentId", e.getId(), "metric", "run_hours", "sourceSeq", "h-2", "value", 500))));
        MvcResult br = mvc.perform(post("/api/metering/readings/batch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk()).andReturn();
        JsonNode batchNode = json.readTree(br.getResponse().getContentAsString());
        assertThat(batchNode.get("total").asInt()).isEqualTo(3);
        assertThat(batchNode.get("accepted").asInt()).isEqualTo(2);
        assertThat(batchNode.get("duplicate").asInt()).isEqualTo(1);
        long workOrderId = batchNode.get("results").get(2).get("workOrderId").asLong();
        assertThat(workOrderId).isPositive();

        // 提醒列表出现待确认项并确认
        MvcResult rem = mvc.perform(get("/api/maintenance/reminders").param("status", "pending")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode reminders = json.readTree(rem.getResponse().getContentAsString());
        long reminderId = -1;
        for (JsonNode r : reminders) {
            if (e.getId().toString().equals(r.get("equipmentId").asText())
                    && "run_hours".equals(r.get("metric").asText())) {
                reminderId = r.get("id").asLong();
            }
        }
        assertThat(reminderId).isPositive();
        mvc.perform(post("/api/maintenance/reminders/" + reminderId + "/confirm")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));

        // 状态：state=work_order_open，溯源读数存在
        MvcResult st = mvc.perform(get("/api/metering/status")
                        .param("equipmentId", e.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode meterRow = json.readTree(st.getResponse().getContentAsString()).get("meters").get(0);
        assertThat(meterRow.get("state").asText()).isEqualTo("work_order_open");
        assertThat(meterRow.get("openWorkOrder").get("sourceType").asText()).isEqualTo("metering");
        assertThat(meterRow.get("openWorkOrder").get("triggerReadingId").asLong()).isPositive();

        // 换表：旧表终值 500、新表起点 0
        String repBody = json.writeValueAsString(Map.of(
                "meterId", meterRow.get("meterId").asLong(),
                "oldFinalValue", new BigDecimal("500"), "newStartValue", new BigDecimal("0"),
                "reason", "到期换表"));
        mvc.perform(post("/api/metering/replacements").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(repBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.offsetValue").value(500));

        // 关闭工单 → 基准落库，状态进入第二周期正常态（新表尚无读数）
        mvc.perform(patch("/api/work-orders/" + workOrderId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));

        MvcResult st2 = mvc.perform(get("/api/metering/status")
                        .param("equipmentId", e.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode row2 = json.readTree(st2.getResponse().getContentAsString()).get("meters").get(0);
        assertThat(row2.get("cycleIndex").asInt()).isEqualTo(2);
        assertThat(row2.get("state").asText()).isEqualTo("normal");
        assertThat(row2.get("baseline").get("workOrderId").asLong()).isEqualTo(workOrderId);

        // 重启工单 → 基准撤回，周期回到 1
        mvc.perform(patch("/api/work-orders/" + workOrderId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"open\"}"))
                .andExpect(status().isOk());
        MvcResult st3 = mvc.perform(get("/api/metering/status")
                        .param("equipmentId", e.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode row3 = json.readTree(st3.getResponse().getContentAsString()).get("meters").get(0);
        assertThat(row3.get("cycleIndex").asInt()).isEqualTo(1);
        assertThat(row3.get("state").asText()).isEqualTo("work_order_open");
    }
}
