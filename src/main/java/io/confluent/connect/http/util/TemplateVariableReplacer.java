package io.confluent.connect.http.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for replacing template variables in strings.
 * Supports variables in the format ${variableName}.
 */
public class TemplateVariableReplacer {
    
    private static final Logger log = LoggerFactory.getLogger(TemplateVariableReplacer.class);
    
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    
    /**
     * Replaces template variables in the input string with values from the provided map
     * 
     * @param input The string containing template variables
     * @param variables Map of variable names to their values
     * @return The string with variables replaced
     */
    public String replace(String input, Map<String, String> variables) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        if (variables == null || variables.isEmpty()) {
            return input;
        }
        
        Matcher matcher = TEMPLATE_PATTERN.matcher(input);
        StringBuilder result = new StringBuilder();
        
        while (matcher.find()) {
            String variableName = matcher.group(1);
            String variableValue = variables.get(variableName);
            
            if (variableValue != null) {
                // Replace the variable with its value, escaping any regex special characters
                matcher.appendReplacement(result, Matcher.quoteReplacement(variableValue));
                log.trace("Replaced template variable ${} with value: {}", variableName, variableValue);
            } else {
                // Keep the original template variable if no replacement is found
                log.debug("No replacement found for template variable: ${}", variableName);
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        
        matcher.appendTail(result);
        
        String finalResult = result.toString();
        if (!finalResult.equals(input)) {
            log.debug("Template replacement completed: {} -> {}", input, finalResult);
        }
        
        return finalResult;
    }
    
    /**
     * Checks if the input string contains any template variables
     * 
     * @param input The string to check
     * @return true if template variables are found, false otherwise
     */
    public boolean containsTemplateVariables(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        
        return TEMPLATE_PATTERN.matcher(input).find();
    }
    
    /**
     * Extracts all template variable names from the input string
     * 
     * @param input The string to extract variables from
     * @return Array of variable names (without the ${} syntax)
     */
    public String[] extractVariableNames(String input) {
        if (input == null || input.isEmpty()) {
            return new String[0];
        }
        
        Matcher matcher = TEMPLATE_PATTERN.matcher(input);
        java.util.List<String> variables = new java.util.ArrayList<>();
        
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        
        return variables.toArray(new String[0]);
    }
}