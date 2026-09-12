package com.puxadinho.patches.ranch;

import com.puxadinho.Debug;
import com.puxadinho.Patch;
import com.puxadinho.asm.ClassWriters;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Refills a wiped ranch. Hooks the throttled zone update and delegates to
 * {@link RanchRespawnGuard}. Independent from the vehicle respawn patch and
 * {@code RanchAnimalAgePatch}, which share the same package but not the same
 * state.
 */
public final class RanchRespawnPatch implements Patch {
    private static final String DESIGNATION_ZONE = "zombie/iso/areas/DesignationZone";
    private static final String GUARD = "com/puxadinho/patches/ranch/RanchRespawnGuard";

    @Override
    public boolean matches(String className) {
        return DESIGNATION_ZONE.equals(className);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = ClassWriters.create(loader, reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals("update") && descriptor.equals("()V")) {
                    return new MethodVisitor(Opcodes.ASM9, original) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "tickZones", "()V", false);
                        }
                    };
                }
                return original;
            }
        };
        reader.accept(visitor, 0);
        Debug.log("ranch respawn patch applied to " + className);
        return writer.toByteArray();
    }
}