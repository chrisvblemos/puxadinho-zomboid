package com.puxadinho.patches.stats;

import com.puxadinho.Patch;
import com.puxadinho.Debug;
import com.puxadinho.asm.ClassWriters;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class PlayerStatsPatch implements Patch {
    private static final String SERVER_DB = "zombie/savefile/ServerPlayerDB";
    private static final String ISO_PLAYER = "zombie/characters/IsoPlayer";
    private static final String CONNECTION = "zombie/core/raknet/UdpConnection";
    private static final String GUARD = "com/puxadinho/patches/stats/StatsGuard";
    private static final String PLAYER_DESC = "Lzombie/characters/IsoPlayer;";
    private static final String CONNECTION_DESC = "Lzombie/core/raknet/UdpConnection;";
    private static final String CHARACTER_DESC = "Lzombie/characters/IsoGameCharacter;";
    private static final String WEAPON_DESC = "Lzombie/inventory/types/HandWeapon;";
    private static final String ON_KILLED_DESC = "(" + CHARACTER_DESC + WEAPON_DESC + "Z)V";
    private static final String KILLED_CALL_DESC = "(" + PLAYER_DESC + CHARACTER_DESC + WEAPON_DESC + ")V";

    @Override
    public boolean matches(String className) {
        return SERVER_DB.equals(className) || ISO_PLAYER.equals(className) || CONNECTION.equals(className);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = ClassWriters.create(loader, reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (SERVER_DB.equals(className)
                    && name.equals("serverUpdateNetworkCharacter")
                    && descriptor.equals("(" + PLAYER_DESC + "I" + CONNECTION_DESC + ")V")) {
                    return onCapture(original);
                }
                if (ISO_PLAYER.equals(className) && name.equals("onKilled") && descriptor.equals(ON_KILLED_DESC)) {
                    return onKilled(original);
                }
                if (CONNECTION.equals(className) && name.equals("setFullyConnected") && descriptor.equals("()V")) {
                    return onConnect(original);
                }
                return original;
            }
        };
        reader.accept(visitor, 0);
        Debug.log("stats patch applied to " + className);
        return writer.toByteArray();
    }

    private static MethodVisitor onCapture(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                visitVarInsn(Opcodes.ALOAD, 1);
                visitVarInsn(Opcodes.ALOAD, 3);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "capture", "(" + PLAYER_DESC + CONNECTION_DESC + ")V", false);
            }
        };
    }

    private static MethodVisitor onKilled(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                visitVarInsn(Opcodes.ALOAD, 0);
                visitVarInsn(Opcodes.ALOAD, 1);
                visitVarInsn(Opcodes.ALOAD, 2);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "killed", KILLED_CALL_DESC, false);
            }
        };
    }

    private static MethodVisitor onConnect(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                visitVarInsn(Opcodes.ALOAD, 0);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "connected", "(" + CONNECTION_DESC + ")V", false);
            }
        };
    }
}
