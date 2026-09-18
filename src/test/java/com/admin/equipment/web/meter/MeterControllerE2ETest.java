package com.admin.equipment.web.meter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeterControllerE2ETest {

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;

    @BeforeEach
    void useHttpComponentsClient() {
        // JDK 自带 HttpURLConnection 不支持 PATCH，换用 HttpComponents 工厂
        rest.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
    }

    private HttpHeaders auth() {
        Map<String, String> body = Map.of("username", "admin", "password", "admin123");
        ResponseEntity<JsonNode> login = rest.postForEntity("/api/auth/login", body, JsonNode.class);
        assertTrue(login.getStatusCode().is2xxSuccessful());
        String token = login.getBody().path("access_token").asText();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void full_meter_maintenance_flow_over_http() throws Exception {
        HttpHeaders h = auth();

        // 新建设备
        Map<String, Object> equipReq = Map.of(
                "code", "E2E-COMP-1", "name", "端到端空压机", "type", "pump");
        ResponseEntity<JsonNode> equip = rest.exchange(
                "/api/equipments", HttpMethod.POST, new HttpEntity<>(equipReq, h), JsonNode.class);
        assertEquals(201, equip.getStatusCode().value());
        long equipmentId = equip.getBody().path("id").asLong();

        // 配置计量项：1000h 阈值、100h 提前量
        Map<String, Object> defReq = Map.of(
                "equipmentId", equipmentId,
                "metric", "running_hours",
                "name", "累计运行小时",
                "unit", "h",
                "threshold", new BigDecimal("1000"),
                "lead", new BigDecimal("100"));
        ResponseEntity<JsonNode> def = rest.exchange(
                "/api/meters/definitions", HttpMethod.POST,
                new HttpEntity<>(defReq, h), JsonNode.class);
        assertEquals(201, def.getStatusCode().value());

        // 批量上报：含一条重复、一条回退
        String batch = """
                {"readings":[
                  {"equipmentId":%d,"metric":"running_hours","sourceSeq":"E2E-1","value":950},
                  {"equipmentId":%d,"metric":"running_hours","sourceSeq":"E2E-1","value":950},
                  {"equipmentId":%d,"metric":"running_hours","sourceSeq":"E2E-2","value":940}
                ]}""".formatted(equipmentId, equipmentId, equipmentId);
        ResponseEntity<JsonNode> br = rest.exchange(
                "/api/meters/readings/batch", HttpMethod.POST,
                new HttpEntity<>(batch, h), JsonNode.class);
        assertEquals(200, br.getStatusCode().value());
        JsonNode results = br.getBody().path("results");
        assertEquals("accepted", results.get(0).path("status").asText());
        assertTrue(results.get(0).path("reminderCreated").asBoolean());
        assertEquals("duplicate", results.get(1).path("status").asText());
        assertEquals("rejected", results.get(2).path("status").asText());
        assertTrue(results.get(2).path("reason").asText().contains("回退"));

        // 剩余量：处于 due_soon，剩余 50
        ResponseEntity<JsonNode> st = rest.exchange(
                "/api/meters/equipment/" + equipmentId + "/status", HttpMethod.GET,
                new HttpEntity<>(h), JsonNode.class);
        JsonNode ms = st.getBody().path("metrics").get(0);
        assertEquals("due_soon", ms.path("state").asText());
        assertEquals(50, ms.path("remaining").decimalValue().intValue());
        assertTrue(ms.path("triggerSource").asText().contains("E2E-1"));

        // 越过阈值自动开工单
        String over = """
                {"readings":[
                  {"equipmentId":%d,"metric":"running_hours","sourceSeq":"E2E-3","value":1000}
                ]}""".formatted(equipmentId);
        ResponseEntity<JsonNode> crossed = rest.exchange(
                "/api/meters/readings/batch", HttpMethod.POST,
                new HttpEntity<>(over, h), JsonNode.class);
        JsonNode c0 = crossed.getBody().path("results").get(0);
        assertTrue(c0.path("workOrderCreated").asBoolean());
        long orderId = c0.path("workOrderId").asLong();

        // 工单展示触发来源（设备维度列表）
        ResponseEntity<JsonNode> orders = rest.exchange(
                "/api/work-orders?equipmentId=" + equipmentId, HttpMethod.GET,
                new HttpEntity<>(h), JsonNode.class);
        JsonNode first = orders.getBody().get(0);
        assertEquals("running_hours", first.path("meterMetric").asText());
        assertTrue(first.path("meterTriggerSource").asText().contains("E2E-3"));

        // 完工写基准
        ResponseEntity<JsonNode> done = rest.exchange(
                "/api/work-orders/" + orderId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "done"), h), JsonNode.class);
        assertEquals(200, done.getStatusCode().value());

        ResponseEntity<JsonNode> after = rest.exchange(
                "/api/meters/equipment/" + equipmentId + "/status", HttpMethod.GET,
                new HttpEntity<>(h), JsonNode.class);
        JsonNode ms2 = after.getBody().path("metrics").get(0);
        assertEquals(1000, ms2.path("cycleStartValue").decimalValue().intValue());
        assertEquals("normal", ms2.path("state").asText());

        // 仪表盘含计量统计
        ResponseEntity<JsonNode> dash = rest.exchange(
                "/api/dashboard/stats", HttpMethod.GET,
                new HttpEntity<>(h), JsonNode.class);
        assertTrue(dash.getBody().path("meter_metric_count").asInt() >= 1);
    }

    @Test
    void unauthorized_request_is_rejected() {
        ResponseEntity<JsonNode> r = rest.getForEntity(
                "/api/meters/definitions", JsonNode.class);
        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void unconfigured_seed_equipment_has_empty_metrics() {
        HttpHeaders h = auth();
        // EQ-1001 一号注塑机未配置计量项，仍能正常返回空 metrics
        ResponseEntity<JsonNode> equipments = rest.exchange(
                "/api/equipments", HttpMethod.GET,
                new HttpEntity<>(h), JsonNode.class);
        long eq1001 = -1;
        for (JsonNode e : equipments.getBody()) {
            if ("EQ-1001".equals(e.path("code").asText())) eq1001 = e.path("id").asLong();
        }
        assertTrue(eq1001 > 0);
        ResponseEntity<JsonNode> st = rest.exchange(
                "/api/meters/equipment/" + eq1001 + "/status", HttpMethod.GET,
                new HttpEntity<>(h), JsonNode.class);
        assertEquals(200, st.getStatusCode().value());
        assertEquals(0, st.getBody().path("metrics").size());
    }
}
