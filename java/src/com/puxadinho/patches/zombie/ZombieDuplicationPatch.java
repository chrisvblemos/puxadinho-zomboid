package com.puxadinho.patches.zombie;

import com.puxadinho.Patch;
import com.puxadinho.asm.ClassWriters;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class ZombieDuplicationPatch implements Patch {
    private static final String MANAGER = "zombie/popman/ZombiePopulationManager";
    private static final String ISO_ZOMBIE = "zombie/characters/IsoZombie";
    private static final String GUARD = "com/puxadinho/patches/zombie/ZombieGuard";
    private static final String ZOMBIE_DESC = "Lzombie/characters/IsoZombie;";
    private static final String CHUNK_DESC = "Lzombie/iso/IsoChunk;";
    private static final String DIR_DESC = "Lzombie/iso/IsoDirections;";

    @Override
    public boolean matches(String className) {
        return MANAGER.equals(className) || ISO_ZOMBIE.equals(className);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception {
        boolean manager = MANAGER.equals(className);
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = ClassWriters.create(loader, reader, ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (manager && name.equals("virtualizeZombie") && descriptor.equals("(" + ZOMBIE_DESC + ")V")) {
                    return guardVirtualize(original);
                }
                if (manager && name.equals("removeChunkFromWorld") && descriptor.equals("(" + CHUNK_DESC + ")V")) {
                    return guardChunk(original);
                }
                if (manager
                    && (name.equals("addZombieStanding") || name.equals("addZombieMoving"))
                    && descriptor.startsWith("(FFF" + DIR_DESC + "I")) {
                    return dedupeEmission(original);
                }
                if (!manager && name.equals("resetForReuse") && descriptor.equals("()V")) {
                    return clearRegistration(original);
                }
                return original;
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private static MethodVisitor guardVirtualize(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label cont = new Label();
                visitVarInsn(Opcodes.ALOAD, 1);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "isRegistered", "(" + ZOMBIE_DESC + ")Z", false);
                visitJumpInsn(Opcodes.IFEQ, cont);
                visitVarInsn(Opcodes.ALOAD, 1);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, ISO_ZOMBIE, "removeFromWorld", "()V", false);
                visitVarInsn(Opcodes.ALOAD, 1);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, ISO_ZOMBIE, "removeFromSquare", "()V", false);
                visitInsn(Opcodes.RETURN);
                visitLabel(cont);
                visitVarInsn(Opcodes.ALOAD, 1);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "mark", "(" + ZOMBIE_DESC + ")V", false);
            }
        };
    }

    private static MethodVisitor guardChunk(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                visitVarInsn(Opcodes.ALOAD, 1);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "markChunk", "(" + CHUNK_DESC + ")V", false);
            }
        };
    }

    private static MethodVisitor dedupeEmission(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label cont = new Label();
                visitVarInsn(Opcodes.FLOAD, 1);
                visitVarInsn(Opcodes.FLOAD, 2);
                visitVarInsn(Opcodes.FLOAD, 3);
                visitVarInsn(Opcodes.ALOAD, 4);
                visitVarInsn(Opcodes.ILOAD, 5);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "duplicate", "(FFF" + DIR_DESC + "I)Z", false);
                visitJumpInsn(Opcodes.IFEQ, cont);
                visitInsn(Opcodes.RETURN);
                visitLabel(cont);
            }
        };
    }

    private static MethodVisitor clearRegistration(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                visitVarInsn(Opcodes.ALOAD, 0);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "clear", "(" + ZOMBIE_DESC + ")V", false);
            }
        };
    }
}
