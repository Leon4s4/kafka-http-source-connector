package io.confluent.connect.http.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration difference analysis between two configurations.
 */
public class ConfigurationDiff {
    
    private final List<ConfigChange> additions;
    private final List<ConfigChange> removals;
    private final List<ConfigChange> modifications;
    private final List<ConfigChange> breakingChanges;
    private final Map<String, Object> metadata;
    
    public ConfigurationDiff() {
        this.additions = new ArrayList<>();
        this.removals = new ArrayList<>();
        this.modifications = new ArrayList<>();
        this.breakingChanges = new ArrayList<>();
        this.metadata = new HashMap<>();
    }
    
    /**
     * Add a configuration addition.
     */
    public void addAddition(String key, String newValue) {
        additions.add(new ConfigChange(key, null, newValue, ChangeType.ADDITION));
    }
    
    /**
     * Add a configuration removal.
     */
    public void addRemoval(String key, String oldValue) {
        removals.add(new ConfigChange(key, oldValue, null, ChangeType.REMOVAL));
    }
    
    /**
     * Add a configuration modification.
     */
    public void addModification(String key, String oldValue, String newValue) {
        modifications.add(new ConfigChange(key, oldValue, newValue, ChangeType.MODIFICATION));
    }
    
    /**
     * Add a breaking change.
     */
    public void addBreakingChange(String key, String oldValue, String newValue) {
        breakingChanges.add(new ConfigChange(key, oldValue, newValue, ChangeType.BREAKING_CHANGE));
    }
    
    /**
     * Add metadata about the diff.
     */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    /**
     * Check if there are any changes.
     */
    public boolean hasChanges() {
        return !additions.isEmpty() || !removals.isEmpty() || !modifications.isEmpty();
    }
    
    /**
     * Check if there are breaking changes.
     */
    public boolean hasBreakingChanges() {
        return !breakingChanges.isEmpty();
    }
    
    /**
     * Get total number of changes.
     */
    public int getTotalChanges() {
        return additions.size() + removals.size() + modifications.size();
    }
    
    /**
     * Get all changes as a single list.
     */
    public List<ConfigChange> getAllChanges() {
        List<ConfigChange> allChanges = new ArrayList<>();
        allChanges.addAll(additions);
        allChanges.addAll(removals);
        allChanges.addAll(modifications);
        return allChanges;
    }
    
    // Getters
    public List<ConfigChange> getAdditions() {
        return new ArrayList<>(additions);
    }
    
    public List<ConfigChange> getRemovals() {
        return new ArrayList<>(removals);
    }
    
    public List<ConfigChange> getModifications() {
        return new ArrayList<>(modifications);
    }
    
    public List<ConfigChange> getBreakingChanges() {
        return new ArrayList<>(breakingChanges);
    }
    
    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }
    
    /**
     * Generate a human-readable summary of changes.
     */
    public String generateSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("Configuration Changes Summary:\n");
        summary.append("============================\n");
        
        if (!hasChanges()) {
            summary.append("No changes detected.\n");
            return summary.toString();
        }
        
        summary.append(String.format("Total Changes: %d\n", getTotalChanges()));
        summary.append(String.format("Additions: %d\n", additions.size()));
        summary.append(String.format("Removals: %d\n", removals.size()));
        summary.append(String.format("Modifications: %d\n", modifications.size()));
        
        if (hasBreakingChanges()) {
            summary.append(String.format("⚠️  Breaking Changes: %d\n", breakingChanges.size()));
        }
        
        summary.append("\nDetailed Changes:\n");
        summary.append("-----------------\n");
        
        // Additions
        if (!additions.isEmpty()) {
            summary.append("Added:\n");
            for (ConfigChange change : additions) {
                summary.append(String.format("  + %s = %s\n", change.getKey(), change.getNewValue()));
            }
        }
        
        // Removals
        if (!removals.isEmpty()) {
            summary.append("Removed:\n");
            for (ConfigChange change : removals) {
                summary.append(String.format("  - %s = %s\n", change.getKey(), change.getOldValue()));
            }
        }
        
        // Modifications
        if (!modifications.isEmpty()) {
            summary.append("Modified:\n");
            for (ConfigChange change : modifications) {
                summary.append(String.format("  ~ %s: %s → %s\n", 
                             change.getKey(), change.getOldValue(), change.getNewValue()));
            }
        }
        
        // Breaking changes
        if (!breakingChanges.isEmpty()) {
            summary.append("⚠️  Breaking Changes:\n");
            for (ConfigChange change : breakingChanges) {
                summary.append(String.format("  ! %s: %s → %s\n", 
                             change.getKey(), change.getOldValue(), change.getNewValue()));
            }
        }
        
        return summary.toString();
    }
    
    @Override
    public String toString() {
        return String.format("ConfigurationDiff{changes=%d, breaking=%d, additions=%d, removals=%d, modifications=%d}",
                           getTotalChanges(), breakingChanges.size(), additions.size(), removals.size(), modifications.size());
    }
    
    /**
     * Configuration change representation.
     */
    public static class ConfigChange {
        private final String key;
        private final String oldValue;
        private final String newValue;
        private final ChangeType type;
        
        public ConfigChange(String key, String oldValue, String newValue, ChangeType type) {
            this.key = key;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.type = type;
        }
        
        public String getKey() {
            return key;
        }
        
        public String getOldValue() {
            return oldValue;
        }
        
        public String getNewValue() {
            return newValue;
        }
        
        public ChangeType getType() {
            return type;
        }
        
        @Override
        public String toString() {
            switch (type) {
                case ADDITION:
                    return String.format("ADD %s = %s", key, newValue);
                case REMOVAL:
                    return String.format("REMOVE %s = %s", key, oldValue);
                case MODIFICATION:
                    return String.format("CHANGE %s: %s → %s", key, oldValue, newValue);
                case BREAKING_CHANGE:
                    return String.format("BREAKING %s: %s → %s", key, oldValue, newValue);
                default:
                    return String.format("UNKNOWN %s", key);
            }
        }
    }
    
    /**
     * Type of configuration change.
     */
    public enum ChangeType {
        ADDITION,
        REMOVAL,
        MODIFICATION,
        BREAKING_CHANGE
    }
}
