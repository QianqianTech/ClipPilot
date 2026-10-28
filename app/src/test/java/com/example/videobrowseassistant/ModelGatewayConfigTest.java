package com.example.videobrowseassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModelGatewayConfigTest {
    @Test
    public void acceptsOnlyHttpsGatewayOrigins() {
        assertEquals("https://ai.example.com", ModelGatewayConfig.normalizeEndpoint(
                "https://AI.example.com/"));
        assertEquals("https://ai.example.com:8443", ModelGatewayConfig.normalizeEndpoint(
                "https://ai.example.com:8443"));
        assertEquals("", ModelGatewayConfig.normalizeEndpoint("http://ai.example.com"));
        assertEquals("", ModelGatewayConfig.normalizeEndpoint("https://ai.example.com/v1"));
        assertEquals("", ModelGatewayConfig.normalizeEndpoint(
                "https://user:pass@ai.example.com"));
        assertEquals("", ModelGatewayConfig.normalizeEndpoint(
                "https://ai.example.com?profile=high"));
    }

    @Test
    public void configNeverUsesEndpointWithoutExplicitEnablement() {
        ModelGatewayConfig.Config disabled = new ModelGatewayConfig.Config(
                "https://ai.example.com", ModelGatewayConfig.Profile.HIGH_RELEVANCE, false);
        ModelGatewayConfig.Config enabled = new ModelGatewayConfig.Config(
                "https://ai.example.com", ModelGatewayConfig.Profile.HIGH_RELEVANCE, true);

        assertTrue(disabled.hasEndpoint());
        assertFalse(disabled.enabled);
        assertTrue(enabled.enabled);
        assertEquals("https://ai.example.com/v1/comment-candidates", enabled.generationUrl());
        assertEquals("ai.example.com", enabled.displayHost());
    }
}
