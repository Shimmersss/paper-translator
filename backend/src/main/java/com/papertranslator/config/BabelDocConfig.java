package com.papertranslator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "babeldoc")
public class BabelDocConfig {

    private boolean enabled = true;
    private String command = "uv run --python 3.12 --with babeldoc==0.6.2 --with bitstring==4.4.0 python";
    private String runnerScript = "scripts/babeldoc_runner.py";
    private String openaiBaseUrl = "https://api.openai.com/v1";
    private String openaiApiKey = "";
    private String openaiModel = "gpt-4o-mini";
    private int timeoutSeconds = 21600;
    private int qps = 2;
    private int resourceCgroupLimitMiB = 2850;
    private int resourceMinAvailableMiB = 400;
    private int resourceMaxSwapUsedMiB = 1200;
    private int maxPagesPerChunk = 1;
    private int resourceRecoveryTimeoutSeconds = 120;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getRunnerScript() {
        return runnerScript;
    }

    public void setRunnerScript(String runnerScript) {
        this.runnerScript = runnerScript;
    }

    public String getOpenaiBaseUrl() {
        return openaiBaseUrl;
    }

    public void setOpenaiBaseUrl(String openaiBaseUrl) {
        this.openaiBaseUrl = openaiBaseUrl;
    }

    public String getOpenaiApiKey() {
        return openaiApiKey;
    }

    public void setOpenaiApiKey(String openaiApiKey) {
        this.openaiApiKey = openaiApiKey;
    }

    public String getOpenaiModel() {
        return openaiModel;
    }

    public void setOpenaiModel(String openaiModel) {
        this.openaiModel = openaiModel;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getQps() {
        return qps;
    }

    public void setQps(int qps) {
        this.qps = qps;
    }

    public int getResourceCgroupLimitMiB() {
        return resourceCgroupLimitMiB;
    }

    public void setResourceCgroupLimitMiB(int resourceCgroupLimitMiB) {
        this.resourceCgroupLimitMiB = resourceCgroupLimitMiB;
    }

    public int getResourceMinAvailableMiB() {
        return resourceMinAvailableMiB;
    }

    public void setResourceMinAvailableMiB(int resourceMinAvailableMiB) {
        this.resourceMinAvailableMiB = resourceMinAvailableMiB;
    }

    public int getResourceMaxSwapUsedMiB() {
        return resourceMaxSwapUsedMiB;
    }

    public void setResourceMaxSwapUsedMiB(int resourceMaxSwapUsedMiB) {
        this.resourceMaxSwapUsedMiB = resourceMaxSwapUsedMiB;
    }

    public int getMaxPagesPerChunk() {
        return maxPagesPerChunk;
    }

    public void setMaxPagesPerChunk(int maxPagesPerChunk) {
        this.maxPagesPerChunk = maxPagesPerChunk;
    }

    public int getResourceRecoveryTimeoutSeconds() {
        return resourceRecoveryTimeoutSeconds;
    }

    public void setResourceRecoveryTimeoutSeconds(int resourceRecoveryTimeoutSeconds) {
        this.resourceRecoveryTimeoutSeconds = resourceRecoveryTimeoutSeconds;
    }
}
