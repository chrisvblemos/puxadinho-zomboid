package com.puxadinho.patches.ranch;

import com.puxadinho.Patch;
import com.puxadinho.asm.ClassWriters;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class RanchAnimalAgePatch implements Patch {
    private static final String TARGET = "zombie/randomizedWorld/randomizedRanch/RandomizedRanchBase";
    private static final String METHOD = "randomizeRanch";
    private static final String DESC = "(Lzombie/iso/zones/Zone;Lzombie/iso/areas/DesignationZoneAnimal;)V";
    private static final double OLD_WORLD_AGE = 60.0;
    private static final double NEVER_OLD = Double.MAX_VALUE;

    @Override
    public boolean matches(String className) {
        return TARGET.equals(className);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = ClassWriters.create(loader, reader, 0);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!METHOD.equals(name) || !DESC.equals(descriptor)) {
                    return original;
                }
                return new MethodVisitor(Opcodes.ASM9, original) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Double && (Double)value == OLD_WORLD_AGE) {
                            super.visitLdcInsn(NEVER_OLD);
                        } else {
                            super.visitLdcInsn(value);
                        }
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }
}
