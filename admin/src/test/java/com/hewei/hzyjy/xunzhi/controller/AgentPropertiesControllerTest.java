package com.hewei.hzyjy.xunzhi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hewei.hzyjy.xunzhi.agent.api.AgentPropertiesController;
import com.hewei.hzyjy.xunzhi.common.convention.result.PageInfo;
import com.hewei.hzyjy.xunzhi.agent.api.io.req.AgentPropertiesReqDTO;
import com.hewei.hzyjy.xunzhi.agent.api.io.resp.AgentPropertiesRespDTO;
import com.hewei.hzyjy.xunzhi.agent.service.AgentPropertiesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentPropertiesControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AgentPropertiesService agentPropertiesService;

    @InjectMocks
    private AgentPropertiesController agentPropertiesController;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(agentPropertiesController).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void testCreate() throws Exception {
        AgentPropertiesReqDTO reqDTO = new AgentPropertiesReqDTO();
        reqDTO.setAgentName("testAgent");

        doNothing().when(agentPropertiesService).create(any(AgentPropertiesReqDTO.class));

        mockMvc.perform(post("/api/xunzhi/v1/agent-properties")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testDelete() throws Exception {
        Long id = 1L;

        doNothing().when(agentPropertiesService).delete(id);

        mockMvc.perform(delete("/api/xunzhi/v1/agent-properties/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testUpdate() throws Exception {
        AgentPropertiesReqDTO reqDTO = new AgentPropertiesReqDTO();
        reqDTO.setId(1L);
        reqDTO.setAgentName("updatedAgent");

        doNothing().when(agentPropertiesService).update(any(AgentPropertiesReqDTO.class));

        mockMvc.perform(put("/api/xunzhi/v1/agent-properties")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void testGetByName() throws Exception {
        String agentName = "testAgent";
        AgentPropertiesRespDTO respDTO = new AgentPropertiesRespDTO();
        respDTO.setId(1L);
        respDTO.setAgentName(agentName);

        when(agentPropertiesService.getByName(agentName)).thenReturn(respDTO);

        mockMvc.perform(get("/api/xunzhi/v1/agent-properties/byName")
                .param("name", agentName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.agentName").value(agentName));
    }

    @Test
    void testGetByPage() throws Exception {
        AgentPropertiesReqDTO reqDTO = new AgentPropertiesReqDTO();
        reqDTO.setPageNum(1);
        reqDTO.setPageSize(10);

        PageInfo<AgentPropertiesRespDTO> pageInfo = new PageInfo<>();
        pageInfo.setCurrent(1L);
        pageInfo.setSize(10L);
        pageInfo.setTotal(1L);

        AgentPropertiesRespDTO respDTO = new AgentPropertiesRespDTO();
        respDTO.setId(1L);
        respDTO.setAgentName("testAgent");
        pageInfo.setRecords(Arrays.asList(respDTO));

        when(agentPropertiesService.getByPage(any(AgentPropertiesReqDTO.class))).thenReturn(pageInfo);

        mockMvc.perform(get("/api/xunzhi/v1/agent-properties")
                .param("pageNum", "1")
                .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].agentName").value("testAgent"));
    }
}
