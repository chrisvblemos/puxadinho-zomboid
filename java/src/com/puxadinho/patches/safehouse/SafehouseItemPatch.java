package com.puxadinho.patches.safehouse;

import com.puxadinho.Patch;
import com.puxadinho.Debug;
import com.puxadinho.asm.ClassWriters;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class SafehouseItemPatch implements Patch {
    private static final String TARGET = "zombie/iso/objects/IsoWorldInventoryObject";
    private static final String METHOD = "isIgnoreRemoveSandbox";
    private static final String DESC = "()Z";
    private static final String SAVE_METHOD = "save";
    private static final String SAVE_DESC = "(Ljava/nio/ByteBuffer;Z)V";
    private static final String CTOR = "<init>";
    private static final String CTOR_DESC = "(Lzombie/inventory/InventoryItem;Lzombie/iso/IsoGridSquare;FFF)V";
    private static final String ADD_METHOD = "addToWorld";
    private static final String ADD_DESC = "()V";
    private static final String GUARD = "com/puxadinho/patches/safehouse/SafehouseGuard";
    private static final String ITEM_DESC = "Lzombie/iso/objects/IsoWorldInventoryObject;";

    @Override
    public boolean matches(String className) {
        return TARGET.equals(className);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = ClassWriters.create(loader, reader, ClassWriter.COMPUTE_FRAMES);
        int[] hooks = new int[4];
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (METHOD.equals(name) && DESC.equals(descriptor)) {
                    hooks[0]++;
                    return nullSafeReturn(original);
                }
                if (SAVE_METHOD.equals(name) && SAVE_DESC.equals(descriptor)) {
                    hooks[1]++;
                    return markForSave(original);
                }
                if (CTOR.equals(name) && CTOR_DESC.equals(descriptor)) {
                    hooks[2]++;
                    return markOnCreate(original);
                }
                if (ADD_METHOD.equals(name) && ADD_DESC.equals(descriptor)) {
                    hooks[3]++;
                    return markOnCreate(original);
                }
                return original;
            }
        };
        reader.accept(visitor, 0);
        Debug.log("safehouse patch applied to " + className
            + " [isIgnoreRemoveSandbox=" + hooks[0]
            + ", save=" + hooks[1]
            + ", ctor=" + hooks[2]
            + ", addToWorld=" + hooks[3] + "]");
        return writer.toByteArray();
    }

    private static MethodVisitor nullSafeReturn(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label skip = new Label();
                visitVarInsn(Opcodes.ALOAD, 0);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "inSafehouse", "(" + ITEM_DESC + ")Z", false);
                visitJumpInsn(Opcodes.IFEQ, skip);
                visitInsn(Opcodes.ICONST_1);
                visitInsn(Opcodes.IRETURN);
                visitLabel(skip);
            }
        };
    }

    private static MethodVisitor markForSave(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                visitVarInsn(Opcodes.ALOAD, 0);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "markForSave", "(" + ITEM_DESC + ")V", false);
            }
        };
    }

    private static MethodVisitor markOnCreate(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.RETURN) {
                    visitVarInsn(Opcodes.ALOAD, 0);
                    visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "mark", "(" + ITEM_DESC + ")V", false);
                }
                super.visitInsn(opcode);
            }
        };
    }
}
