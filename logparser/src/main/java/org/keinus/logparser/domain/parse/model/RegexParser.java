package org.keinus.logparser.domain.parse.model;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.keinus.logparser.domain.model.LogEvent;


public class RegexParser implements IParser {
    /**
     * Parser that uses regular expressions to extract key-value pairs from log messages.
     * The pattern should contain two capturing groups: one for the key and one for the value.
     */
    private Pattern regex = null;

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
            while(m.find()){
                if (m.groupCount() >= 2) {
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

}
