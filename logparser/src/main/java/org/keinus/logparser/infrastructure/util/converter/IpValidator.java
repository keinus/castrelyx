package org.keinus.logparser.infrastructure.util.converter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IpValidator {
    private static final Logger log = LoggerFactory.getLogger(IpValidator.class);
    
    // Simple regex for quick check before calling InetAddress (perf optimization)
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");
    
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^[0-9a-fA-F:]+$"); // Loose check, let InetAddress do the heavy lifting

    /**
     * Validates IP address string.
     * Returns valid IP string or null if invalid.
     */
    public static String validate(Object ipObj) {
        if (ipObj == null) {
            return null;
        }
        
        String ipStr = ipObj.toString().trim();
        if (ipStr.isEmpty() || ipStr.equals("-")) {
            return null;
        }

        try {
            if (IPV4_PATTERN.matcher(ipStr).matches() || IPV6_PATTERN.matcher(ipStr).matches()) {
                // Canonicalize
                InetAddress inet = InetAddress.getByName(ipStr);
                return inet.getHostAddress();
            }
        } catch (UnknownHostException e) {
            log.trace("Invalid IP format: {}", ipStr);
        }
        
        return null;
    }
}
