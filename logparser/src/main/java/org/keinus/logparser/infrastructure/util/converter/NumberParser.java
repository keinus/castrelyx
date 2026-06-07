package org.keinus.logparser.infrastructure.util.converter;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NumberParser {
    private static final Logger log = LoggerFactory.getLogger(NumberParser.class);

    public static Long parseLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        
        String str = obj.toString().trim().replace(",", ""); // Handle "1,000"
        if (str.isEmpty()) return null;

        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            // Try parsing as double then cast (e.g. "123.0")
            try {
                return (long) Double.parseDouble(str);
            } catch (NumberFormatException ex) {
                log.trace("Failed to parse Long: {}", str);
                return null;
            }
        }
    }

    public static Integer parseInt(Object obj) {
        Long val = parseLong(obj);
        return val != null ? val.intValue() : null;
    }

    public static Double parseDouble(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        
        String str = obj.toString().trim().replace(",", "");
        if (str.isEmpty()) return null;

        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            log.trace("Failed to parse Double: {}", str);
            return null;
        }
    }
    
    public static BigDecimal parseBigDecimal(Object obj) {
         if (obj == null) return null;
         String str = obj.toString().trim().replace(",", "");
         if (str.isEmpty()) return null;
         try {
             return new BigDecimal(str);
         } catch(Exception e) {
             return null;
         }
    }
}
