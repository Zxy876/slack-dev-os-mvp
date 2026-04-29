package com.asyncaiflow.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "asyncaiflow.dispatch")
public class ActionCapabilityResolver {

    private Map<String, String> capabilityMapping = new LinkedHashMap<>();

    public String resolveRequiredCapability(String actionType) {
        String normalizedType = normalize(actionType);
        String mappedCapability = capabilityMapping.get(normalizedType);
        if (mappedCapability == null || mappedCapability.isBlank()) {
            return normalizedType;
        }
        return normalize(mappedCapability);
    }

    public Map<String, String> getCapabilityMapping() {
        return capabilityMapping;
    }

    public void setCapabilityMapping(Map<String, String> capabilityMapping) {
        this.capabilityMapping = capabilityMapping == null ? new LinkedHashMap<>() : new LinkedHashMap<>(capabilityMapping);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
