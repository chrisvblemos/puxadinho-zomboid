package com.puxadinho.patches.perf;

import com.puxadinho.Debug;
import com.puxadinho.Patch;
import com.puxadinho.asm.ClassWriters;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Hooks the server map's per-tick {@code preupdate} and delegates to
 * {@link PerfGuard}, which throttles itself to the configured sample interval.
 */
public final class PerfPatch implements Patch {
    private static final String SERVER_MAP = "zombie/network/ServerMap";
    private static final String GUARD = "com/puxadinho/patches/perf/PerfGuard";

    @Override
    public boolean matches(String className) {
        return SERVER_MAP.equals(className);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = ClassWriters.create(loader, reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals("preupdate") && descriptor.equals("()V")) {
                    return new MethodVisitor(Opcodes.ASM9, original) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "tick", "()V", false);
                        }
                    };
                }
                return original;
            }
        };
        reader.accept(visitor, 0);
        Debug.log("perf patch applied to " + className);
        return writer.toByteArray();
    }
}
