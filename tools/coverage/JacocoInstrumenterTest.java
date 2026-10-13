package rules_clojure.coverage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class JacocoInstrumenterTest {

    private static final long SOURCE_TIME = 2_145_916_800_000L;
    private static final long CLASS_TIME = SOURCE_TIME + 2000L;

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void instrument_preservesTimestamps_writesUninstrumentedAndPaths() throws Exception {
        Path in = tmp.newFile("in.jar").toPath();
        Path out = tmp.newFile("out.jar").toPath();
        writeJar(
                in,
                entry("foo/core.clj", SOURCE_TIME, "(ns foo.core)".getBytes(StandardCharsets.UTF_8)),
                entry(
                        "foo/core.class",
                        CLASS_TIME,
                        classBytes("foo/core", "core.clj", /* body= */ true)));

        List<String> args =
                Arrays.asList(in.toString(), out.toString(), "workspace/foo/core.clj");
        JacocoInstrumenter.instrument(args, silent());

        Map<String, byte[]> zipped = readZip(out);
        assertTrue(zipped.containsKey("foo/core.class"));
        assertTrue(zipped.containsKey("foo/core.class.uninstrumented"));
        assertTrue(zipped.containsKey("foo/core.clj"));
        assertTrue(zipped.containsKey("out-paths-for-coverage.txt"));

        String paths =
                new String(zipped.get("out-paths-for-coverage.txt"), StandardCharsets.UTF_8);
        assertEquals("workspace/foo/core.clj\n", paths);

        assertFalse(
                Arrays.equals(
                        zipped.get("foo/core.class"), zipped.get("foo/core.class.uninstrumented")));
        assertTrue(
                Arrays.equals(
                        zipped.get("foo/core.class.uninstrumented"),
                        classBytes("foo/core", "core.clj", true)));

        try (ZipFile zf = new ZipFile(out.toFile())) {
            ZipEntry classEntry = zf.getEntry("foo/core.class");
            assertNotNull(classEntry);
            long mtime = classEntry.getLastModifiedTime().toMillis();
            assertTrue(
                    "class mtime should stay near CLASS_TIME, got " + mtime,
                    Math.abs(mtime - CLASS_TIME) < 3000);
        }
    }

    @Test
    public void instrument_keepsClassesItCannotInstrument() throws Exception {
        Path in = tmp.newFile("bad.jar").toPath();
        Path out = tmp.newFile("bad-out.jar").toPath();
        byte[] notAClass = "not a class".getBytes(StandardCharsets.UTF_8);
        writeJar(in, entry("foo/bad.class", CLASS_TIME, notAClass));

        ByteArrayOutputStream log = new ByteArrayOutputStream();
        JacocoInstrumenter.instrument(
                Arrays.asList(in.toString(), out.toString()), new PrintStream(log, true));

        Map<String, byte[]> zipped = readZip(out);
        assertTrue(Arrays.equals(notAClass, zipped.get("foo/bad.class")));
        assertFalse(zipped.containsKey("foo/bad.class.uninstrumented"));
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("skip foo/bad.class"));
    }

    @Test
    public void expandArgsFile_readsParamFile() throws Exception {
        Path argsFile = tmp.newFile("args.txt").toPath();
        Files.write(argsFile, Arrays.asList("a.jar", "b.jar", "src/x.clj"));
        List<String> expanded =
                JacocoInstrumenter.expandArgsFile(new String[] {"@" + argsFile});
        assertEquals(Arrays.asList("a.jar", "b.jar", "src/x.clj"), expanded);
    }

    private static Map<String, byte[]> readZip(Path jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) {
                    out.put(e.getName(), zf.getInputStream(e).readAllBytes());
                }
            }
        }
        return out;
    }

    private static PrintStream silent() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static final class Entry {
        final String name;
        final long mtime;
        final byte[] bytes;

        Entry(String name, long mtime, byte[] bytes) {
            this.name = name;
            this.mtime = mtime;
            this.bytes = bytes;
        }
    }

    private static Entry entry(String name, long mtime, byte[] bytes) {
        return new Entry(name, mtime, bytes);
    }

    private static void writeJar(Path jar, Entry... entries) throws IOException {
        try (ZipOutputStream zos =
                new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(jar)))) {
            for (Entry e : entries) {
                ZipEntry ze = new ZipEntry(e.name);
                ze.setLastModifiedTime(FileTime.fromMillis(e.mtime));
                zos.putNextEntry(ze);
                zos.write(e.bytes);
                zos.closeEntry();
            }
        }
    }

    private static byte[] classBytes(String internalName, String sourceFile, boolean withMethod) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitSource(sourceFile, null);
        MethodVisitor ctor =
                cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        if (withMethod) {
            MethodVisitor mv =
                    cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "f", "()I", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
