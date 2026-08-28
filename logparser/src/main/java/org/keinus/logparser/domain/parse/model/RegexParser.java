package org.keinus.logparser.domain.parse.model;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.keinus.logparser.domain.model.LogEvent;


public class RegexParser implements IParser {
    /**
     * Named capturing groups become event fields. Without named groups, the first
     * two capturing groups retain the existing key/value extraction behavior.
     */
    private Pattern regex = null;
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_.-]*)\\s*=\\s*(?:\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"|(\\S+))");

    @Override
    public void init(Object param) {
        String pattern = (String)param;
        this.regex = Pattern.compile(pattern);
    }

    @Override
    public boolean parse(LogEvent logEvent) {
        try {
            String message = logEvent.getOriginalText();
            Matcher m = regex.matcher(message);
            Map<String, Object> map = new HashMap<>();
            Map<String, Integer> namedGroups = regex.namedGroups();
            while(m.find()){
                if (!namedGroups.isEmpty()) {
                    for (String name : namedGroups.keySet()) {
                        String value = m.group(name);
                        if (value != null) {
                            map.put(name, value);
                            if ("attributes".equalsIgnoreCase(name)) {
                                addAttributeFields(map, value);
                            }
                        }
                    }
                } else if (m.groupCount() >= 2) {
                    map.put(m.group(1), m.group(2));
                } else {
                    logEvent.markAsError("Regex pattern must have at least 2 capturing groups, found: " + m.groupCount());
                    return false;
                }
            }

            if (!map.isEmpty()) {
                logEvent.setFields(map);
                return true;
            }
        } catch (Exception e) {
            logEvent.markAsError("Regex parsing failed: " + e.getMessage());
        }
        return false;
    }

    private void addAttributeFields(Map<String, Object> fields, String attributes) {
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(attributes);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? unescape(matcher.group(2)) : matcher.group(3);
            fields.put(matcher.group(1), value);
        }
    }

    private String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

}
