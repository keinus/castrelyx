package org.keinus.logparser.domain.input.model.snmp;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SnmpMibParserTest {
    private final SnmpMibParser parser = new SnmpMibParser();
    private String example() throws Exception {
        try (var stream = getClass().getResourceAsStream("/snmp/EXAMPLE-MIB.mib")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test void resolvesScalarColumnTextualConventionAndFiltersUnreadableObjects() throws Exception {
        var result = parser.parse(List.of(new SnmpMibParser.MibFile("EXAMPLE-MIB.mib", example())));
        assertThat(result.modules()).containsExactly("EXAMPLE-MIB");
        assertThat(result.objects()).extracting(SnmpMibParser.MibObject::name)
                .containsExactly("deviceLabel", "portBytes", "temperature");
        var scalar = result.objects().getFirst();
        assertThat(scalar.scalar()).isTrue();
        assertThat(scalar.oid()).isEqualTo("1.3.6.1.4.1.32473.1");
        assertThat(scalar.syntax()).isEqualTo("OCTET_STRING");
        var column = result.objects().get(1);
        assertThat(column.scalar()).isFalse();
        assertThat(column.oid()).isEqualTo("1.3.6.1.4.1.32473.3.1.2");
        assertThat(column.indexes()).containsExactly("portIndex");
        assertThat(column.syntax()).isEqualTo("COUNTER_64");
        assertThat(column.units()).isEqualTo("bytes");
    }
    @Test void resolvesUploadedDependenciesAndDuplicateSymbolNamesByModule() {
        String base = "BASE-MIB DEFINITIONS ::= BEGIN IMPORTS enterprises FROM SNMPv2-SMI; root OBJECT IDENTIFIER ::= { enterprises 32473 } END";
        String child = """
                CHILD-MIB DEFINITIONS ::= BEGIN
                IMPORTS root FROM BASE-MIB OBJECT-TYPE, Integer32 FROM SNMPv2-SMI;
                temperature OBJECT-TYPE SYNTAX Integer32 MAX-ACCESS read-only STATUS current
                DESCRIPTION "Sensor" ::= { root 9 }
                END
                """;
        assertThatThrownBy(() -> parser.parse(List.of(new SnmpMibParser.MibFile("child", child))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BASE-MIB");
        var result = parser.parse(List.of(new SnmpMibParser.MibFile("child", child), new SnmpMibParser.MibFile("base", base)));
        assertThat(result.objects()).hasSize(1);
        assertThat(result.objects().getFirst().oid()).isEqualTo("1.3.6.1.4.1.32473.9");
    }
    @Test void supportsSmiV1Access() {
        String mib = """
                LEGACY-MIB DEFINITIONS ::= BEGIN
                IMPORTS enterprises FROM RFC1155-SMI OBJECT-TYPE FROM RFC-1212;
                legacy OBJECT IDENTIFIER ::= { enterprises 32473 }
                value OBJECT-TYPE SYNTAX INTEGER ACCESS read-only STATUS mandatory
                DESCRIPTION "Legacy value" ::= { legacy 7 }
                END
                """;
        assertThat(parser.parse(List.of(new SnmpMibParser.MibFile("legacy", mib))).objects())
                .singleElement().satisfies(object -> assertThat(object.oid()).isEqualTo("1.3.6.1.4.1.32473.7"));
    }
    @Test void rejectsInvalidSyntaxEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> parser.parse(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(List.of(new SnmpMibParser.MibFile("bad", "not a MIB")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(List.of(new SnmpMibParser.MibFile("huge", "x".repeat(SnmpMibParser.MAX_FILE_BYTES + 1)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 MiB");
    }
}
