package com.example.loadgenerator.model;

public class ScenarioRequest {
    // Spike
    private int peakRps;
    private int rampUpSeconds;
    private int sustainSeconds;
    private int rampDownSeconds;

    // Soak
    private int requestsPerSecond;
    private int durationMinutes;

    // Stress
    private int startRps;
    private int stepRps;
    private int stepDurationSeconds;
    private int maxRps;

    // Chaos
    private int durationSeconds;
    private int errorMixPercent;

    public int getPeakRps() { return peakRps; }
    public void setPeakRps(int peakRps) { this.peakRps = peakRps; }
    public int getRampUpSeconds() { return rampUpSeconds; }
    public void setRampUpSeconds(int rampUpSeconds) { this.rampUpSeconds = rampUpSeconds; }
    public int getSustainSeconds() { return sustainSeconds; }
    public void setSustainSeconds(int sustainSeconds) { this.sustainSeconds = sustainSeconds; }
    public int getRampDownSeconds() { return rampDownSeconds; }
    public void setRampDownSeconds(int rampDownSeconds) { this.rampDownSeconds = rampDownSeconds; }
    public int getRequestsPerSecond() { return requestsPerSecond; }
    public void setRequestsPerSecond(int requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public int getStartRps() { return startRps; }
    public void setStartRps(int startRps) { this.startRps = startRps; }
    public int getStepRps() { return stepRps; }
    public void setStepRps(int stepRps) { this.stepRps = stepRps; }
    public int getStepDurationSeconds() { return stepDurationSeconds; }
    public void setStepDurationSeconds(int stepDurationSeconds) { this.stepDurationSeconds = stepDurationSeconds; }
    public int getMaxRps() { return maxRps; }
    public void setMaxRps(int maxRps) { this.maxRps = maxRps; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
    public int getErrorMixPercent() { return errorMixPercent; }
    public void setErrorMixPercent(int errorMixPercent) { this.errorMixPercent = errorMixPercent; }
}
