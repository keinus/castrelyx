package org.keinus.logparser.domain.input.model.snmp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.snmp4j.smi.*;

final class SnmpValues {
    private SnmpValues() {}

    static Map<String, Object> binding(VariableBinding binding) {
        Variable variable = binding.getVariable();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oid", binding.getOid().toDottedString());
        result.put("syntax", variable.getSyntaxString());
        result.put("status", variable.isException() ? variable.toString() : "success");
        if (!variable.isException()) {
            // Decimal text plus syntax preserves the full unsigned Counter64 range in JSON consumers.
            Object value = variable instanceof Counter64 ? variable.toString()
                    : variable instanceof Integer32 || variable instanceof UnsignedInteger32 ? variable.toLong()
                    : variable.toString();
            result.put("value", value);
            if (variable instanceof OctetString octets) result.put("hex", octets.toHexString());
        }
        return result;
    }
}
