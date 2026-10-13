package rules_clojure.coverage;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator;

/**
 * Offline-instruments a clojure_library jar for {@code bazel coverage}. The output has the jar
 * shape that JavaBuilder makes for {@code java_library}: instrumented {@code .class} entries,
 * the originals as {@code .class.uninstrumented}, and a {@code *-paths-for-coverage.txt} entry.
 *
 * <p>Entry mtimes are kept. {@code clojure.lang.RT.load} loads a {@code .clj} file instead of
 * its {@code __init.class} if the source is newer, and then no line data is recorded.
 *
 * <p>Usage: {@code JacocoInstrumenter @argsfile}, one arg per line: in jar, out jar, then the
 * workspace-relative source paths.
 */
public final class JacocoInstrumenter {

    public static void main(String[] args) throws IOException {
        instrument(expandArgsFile(args), System.err);
    }

    static List<String> expandArgsFile(String[] args) throws IOException {
        if (args.length == 1 && args[0].startsWith("@")) {
            return Files.readAllLines(Paths.get(args[0].substring(1)), StandardCharsets.UTF_8);
        }
        return Arrays.asList(args);
    }

    static void instrument(List<String> args, PrintStream log) throws IOException {
        if (args.size() < 2) {
            throw new IOException("usage: JacocoInstrumenter in.jar out.jar [src ...]");
        }
        Path inJar = Paths.get(args.get(0));
        Path outJar = Paths.get(args.get(1));
        List<String> sourcePaths = args.subList(2, args.size());
        Instrumenter instrumenter = new Instrumenter(new OfflineInstrumentationAccessGenerator());

        try (ZipFile in = new ZipFile(inJar.toFile());
                ZipOutputStream out =
                        new ZipOutputStream(
                                new BufferedOutputStream(Files.newOutputStream(outJar)))) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                FileTime time = entry.getLastModifiedTime();
                byte[] bytes = entry.isDirectory() ? new byte[0] : readAll(in.getInputStream(entry));
                if (!name.endsWith(".class")) {
                    writeEntry(out, name, time, bytes);
                    continue;
                }
                byte[] instrumented;
                try {
                    instrumented = instrumenter.instrument(bytes, name);
                } catch (IOException e) {
                    log.println("JacocoInstrumenter: skip " + name + ": " + e.getMessage());
                    writeEntry(out, name, time, bytes);
                    continue;
                }
                writeEntry(out, name, time, instrumented);
                writeEntry(out, name + ".uninstrumented", time, bytes);
            }

            String pathsEntry =
                    outJar.getFileName().toString().replaceAll("\\.jar$", "")
                            + "-paths-for-coverage.txt";
            StringBuilder sb = new StringBuilder();
            for (String src : sourcePaths) {
                sb.append(src).append('\n');
            }
            writeEntry(
                    out,
                    pathsEntry,
                    FileTime.fromMillis(0L),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeEntry(ZipOutputStream out, String name, FileTime time, byte[] bytes)
            throws IOException {
        ZipEntry e = new ZipEntry(name);
        if (time != null) {
            e.setLastModifiedTime(time);
        }
        out.putNextEntry(e);
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (is) {
            return is.readAllBytes();
        }
    }

    private JacocoInstrumenter() {}
}
