package rules_clojure;

import clojure.asm.ClassReader;
import clojure.asm.ClassVisitor;
import clojure.asm.ClassWriter;
import clojure.asm.Label;
import clojure.asm.MethodVisitor;
import clojure.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

/**
 * Bytecode patches applied to clojure.jar classes as they are loaded
 * into a compile classloader, so that AOT output is a function of the
 * source rather than of compile order.
 *
 * Two sources of nondeterminism in clojure.lang.Compiler:
 *
 * - RT.nextID() is a single process-wide counter used for fn class
 *   names, gensyms, reader auto-gensyms and loop/letfn locals. In a
 *   multiplexed worker with parallel compiles, the numbers a namespace
 *   gets depend on whatever else is compiling. We add a ThreadLocal
 *   override, RT.idOverride, which rules-clojure.compile sets to a
 *   fresh per-namespace counter.
 *
 * - ObjExpr.closes is a PersistentHashMap keyed by LocalBinding, which
 *   uses identity hashCode. Closed-over field order, ctor signatures
 *   and call sites are emitted by iterating it. We give LocalBinding a
 *   hashCode derived from its name and slot index. equals stays
 *   identity.
 *
 * Both patches are skipped if the class doesn't have the expected
 * shape, so unknown clojure versions load unmodified.
 *
 * This is Java rather than Clojure because the visitors subclass ASM's
 * abstract classes, and Clojure's `proxy` emits constructors in
 * getDeclaredConstructors() order, which varies between JVMs. That
 * would make the worker jar itself nondeterministic.
 */
public final class DeterministicPatches {
    private DeterministicPatches() {}

    private static final int API = Opcodes.ASM6;

    public static final String RT = "clojure.lang.RT";
    public static final String LOCAL_BINDING = "clojure.lang.Compiler$LocalBinding";

    /** Name of the ThreadLocal&lt;AtomicInteger&gt; static field added to clojure.lang.RT */
    public static final String OVERRIDE_FIELD = "idOverride";

    private static final String RT_INTERNAL = "clojure/lang/RT";
    private static final String LOCAL_BINDING_INTERNAL = "clojure/lang/Compiler$LocalBinding";
    private static final String ATOMIC_INT = "java/util/concurrent/atomic/AtomicInteger";
    private static final String ATOMIC_INT_DESC = "L" + ATOMIC_INT + ";";
    private static final String THREAD_LOCAL = "java/lang/ThreadLocal";
    private static final String THREAD_LOCAL_DESC = "L" + THREAD_LOCAL + ";";

    /** True if `name` (a binary class name) is one we patch */
    public static boolean isPatched(String name) {
        return RT.equals(name) || LOCAL_BINDING.equals(name);
    }

    /** Given a binary class name and its bytes, return the (possibly patched) bytes */
    public static byte[] patch(String name, byte[] bytes) {
        if (RT.equals(name)) {
            return patchRT(bytes);
        } else if (LOCAL_BINDING.equals(name)) {
            return patchLocalBinding(bytes);
        }
        return bytes;
    }

    private static final class ClassInfo extends ClassVisitor {
        int version;
        final Set<String> fields = new HashSet<>();
        final Set<String> methods = new HashSet<>();

        ClassInfo(byte[] bytes) {
            super(API);
            new ClassReader(bytes).accept(this, ClassReader.SKIP_CODE);
        }

        @Override
        public void visit(int version, int access, String name, String sig, String superName, String[] ifaces) {
            this.version = version;
        }

        @Override
        public clojure.asm.FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
            fields.add(name + " " + desc);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
            methods.add(name + desc);
            return null;
        }
    }

    // StackMapTable frames are required from class version 50 (java 6)
    // and ignored before that. Low 16 bits are the major version.
    private static boolean frames(int version) {
        return (version & 0xFFFF) >= 50;
    }

    /**
     * Add `public static final ThreadLocal idOverride` to clojure.lang.RT and
     * make nextID() prefer it over the global counter
     */
    static byte[] patchRT(byte[] bytes) {
        ClassInfo info = new ClassInfo(bytes);
        if (!(info.fields.contains("id " + ATOMIC_INT_DESC)
              && info.methods.contains("nextID()I")
              && info.methods.contains("<clinit>()V")
              && !info.fields.contains(OVERRIDE_FIELD + " " + THREAD_LOCAL_DESC))) {
            return bytes;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                if ("nextID".equals(name) && "()I".equals(desc)) {
                    emitNextID(mv, info.version);
                    // drop the original body
                    return null;
                }
                if ("<clinit>".equals(name)) {
                    // initialize idOverride before anything in RT's static init can call nextID
                    return new MethodVisitor(API, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            visitTypeInsn(Opcodes.NEW, THREAD_LOCAL);
                            visitInsn(Opcodes.DUP);
                            visitMethodInsn(Opcodes.INVOKESPECIAL, THREAD_LOCAL, "<init>", "()V", false);
                            visitFieldInsn(Opcodes.PUTSTATIC, RT_INTERNAL, OVERRIDE_FIELD, THREAD_LOCAL_DESC);
                        }
                    };
                }
                return mv;
            }

            @Override
            public void visitEnd() {
                cv.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                              OVERRIDE_FIELD, THREAD_LOCAL_DESC, null, null).visitEnd();
                super.visitEnd();
            }
        }, 0);
        return cw.toByteArray();
    }

    /*
     * static int nextID() {
     *   AtomicInteger a = (AtomicInteger) idOverride.get();
     *   if (a == null) a = id;
     *   return a.getAndIncrement(); }
     */
    private static void emitNextID(MethodVisitor mv, int version) {
        Label l = new Label();
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, RT_INTERNAL, OVERRIDE_FIELD, THREAD_LOCAL_DESC);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD_LOCAL, "get", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ATOMIC_INT);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IFNONNULL, l);
        mv.visitFieldInsn(Opcodes.GETSTATIC, RT_INTERNAL, "id", ATOMIC_INT_DESC);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        mv.visitLabel(l);
        if (frames(version)) {
            mv.visitFrame(Opcodes.F_FULL, 1, new Object[]{ATOMIC_INT}, 0, new Object[0]);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ATOMIC_INT, "getAndIncrement", "()I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
    }

    /**
     * Add `public int hashCode() { return name.hashCode() * 31 + idx; }` to
     * Compiler$LocalBinding
     */
    static byte[] patchLocalBinding(byte[] bytes) {
        ClassInfo info = new ClassInfo(bytes);
        if (!(info.fields.contains("name Ljava/lang/String;")
              && info.fields.contains("idx I")
              && !info.methods.contains("hashCode()I"))) {
            return bytes;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(API, cw) {
            @Override
            public void visitEnd() {
                MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, LOCAL_BINDING_INTERNAL, "name", "Ljava/lang/String;");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false);
                mv.visitIntInsn(Opcodes.BIPUSH, 31);
                mv.visitInsn(Opcodes.IMUL);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, LOCAL_BINDING_INTERNAL, "idx", "I");
                mv.visitInsn(Opcodes.IADD);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(2, 1);
                mv.visitEnd();
                super.visitEnd();
            }
        }, 0);
        return cw.toByteArray();
    }
}
