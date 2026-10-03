package org.keinus.logparser.domain.input.model.snmp;

import org.jsmiparser.parser.SmiDefaultParser;
import org.jsmiparser.phase.xref.*;
import org.jsmiparser.smi.*;
import org.jsmiparser.util.problem.AbstractProblemEventHandler;
import org.jsmiparser.util.problem.ProblemEvent;
import org.jsmiparser.util.problem.annotations.ProblemSeverity;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parses uploaded SMI documents only; IMPORTS never fetches network or local files. */
@Component
public class SnmpMibParser {
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 4 * MAX_FILE_BYTES;
    public record MibFile(String name, String content) {}
    public record MibObject(String module, String name, String oid, String syntax,
                            String access, String description, String units,
                            boolean scalar, String table, List<String> indexes) {}
    public record Result(List<String> modules, List<MibObject> objects, List<String> warnings) {}

    public Result parse(List<MibFile> files) {
        if (files == null || files.isEmpty() || files.size() > 16) {
            throw new IllegalArgumentException("Upload between 1 and 16 MIB files, including imported vendor modules.");
        }
        Problems problems = new Problems();
        try {
            List<URL> inputs = new ArrayList<>();
            int total = 0;
            for (int i = 0; i < files.size(); i++) {
                MibFile file = files.get(i);
                if (file == null || file.content() == null || file.content().isBlank()) {
                    throw new IllegalArgumentException("Each MIB file must contain SMI text.");
                }
                byte[] bytes = file.content().replaceFirst("^\\uFEFF", "").getBytes(StandardCharsets.UTF_8);
                total += bytes.length;
                if (bytes.length > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                    throw new IllegalArgumentException("MIB files must be at most 1 MiB each and 4 MiB in total.");
                }
                inputs.add(memoryUrl(i, bytes));
            }
            SmiDefaultParser parser = new SmiDefaultParser(problems);
            SmiMib mib = new SmiMib(parser.getOptions(), new SmiJavaCodeNamingStrategy("mib"));
            parser.getFileParserPhase().setInputUrls(inputs);
            parser.getFileParserPhase().process(mib);
            problems.check();
            Set<String> uploadedModules = new HashSet<>();
            mib.getModules().stream().filter(module -> module != mib.getInternalModule())
                    .forEach(module -> uploadedModules.add(module.getId()));
            if (uploadedModules.isEmpty()) throw new IllegalArgumentException("No MIB module was found in the uploaded files.");
            for (String module : List.of("SNMPv2-SMI", "SNMPv2-TC", "RFC1155-SMI")) {
                if (mib.findModule(module) == null) {
                    URL core = SnmpMibParser.class.getResource("/snmp/mibs/" + module + ".txt");
                    parser.getFileParserPhase().parse(mib, core, module);
                }
            }
            problems.check();
            parser.getXRefPhase().addSymbolDefiners(new SNMPv2_CONFSymbolDefiner(),
                    new RFC_1212SymbolDefiner(), new RFC_1215SymbolDefiner());
            parser.getXRefPhase().process(mib);
            problems.check();
            List<MibObject> objects = new ArrayList<>();
            for (SmiVariable variable : mib.getVariables()) {
                if (!uploadedModules.contains(variable.getModule().getId())
                        || variable.getAccessAll() == null || !variable.getAccessAll().isReadable()) continue;
                SmiRow row = variable.getRow();
                List<String> indexes = new ArrayList<>();
                Set<SmiRow> visited = new HashSet<>();
                SmiRow indexedRow = row;
                while (indexedRow != null && visited.add(indexedRow)) {
                    if (indexedRow.getIndexes() != null) {
                        for (SmiIndex index : indexedRow.getIndexes()) {
                            indexes.add(index.getColumn().getId() + (index.isImplied() ? " (IMPLIED)" : ""));
                        }
                        break;
                    }
                    indexedRow = indexedRow.getAugments();
                }
                objects.add(new MibObject(variable.getModule().getId(), variable.getId(), variable.getOidStr(),
                        variable.getPrimitiveType().name(), variable.getAccessAll().toString(),
                        variable.getDescription(), variable.getUnits(), variable.isScalar(),
                        row == null ? null : row.getId(), indexes));
            }
            if (objects.size() > 5000) throw new IllegalArgumentException("Upload fewer modules; at most 5000 readable OIDs are supported per import.");
            objects.sort(Comparator.comparing(MibObject::module).thenComparing(MibObject::name));
            return new Result(uploadedModules.stream().sorted().toList(), objects, problems.warnings);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception | StackOverflowError e) {
            problems.check();
            throw new IllegalArgumentException("Unable to resolve MIB definitions. Check syntax and upload all imported vendor MIBs.", e);
        }
    }

    private static URL memoryUrl(int index, byte[] bytes) throws Exception {
        return new URL(null, "memory:/upload-" + (index + 1), new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    @Override public void connect() {}
                    @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                };
            }
        });
    }

    private static final class Problems extends AbstractProblemEventHandler {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        @Override public void handle(ProblemEvent event) {
            super.handle(event);
            List<String> target = event.getSeverity() == ProblemSeverity.ERROR || event.getSeverity() == ProblemSeverity.FATAL
                    ? errors : warnings;
            if (target.size() < 20) target.add(event.getLocalizedMessage());
        }
        void check() {
            if (isNotOk()) throw new IllegalArgumentException("MIB import failed: " + String.join("; ", errors)
                    + ". Include the referenced MIB files in the same upload.");
        }
    }
}
