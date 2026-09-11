package com.puxadinho.patches.death;

import com.puxadinho.Debug;
import com.puxadinho.Patch;
import com.puxadinho.asm.ClassWriters;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Hooks {@code IsoPlayer.onKilled} and announces the death to server chat with
 * a message chosen by the cause of death. Kept separate from the player-stats
 * patch so the announcement can be configured (or disabled) without touching
 * the statistics database.
 */
public final class DeathMessagePatch implements Patch {
    private static final String ISO_PLAYER = "zombie/characters/IsoPlayer";
    private static final String GUARD = "com/puxadinho/patches/death/DeathMessageGuard";
    private static final String PLAYER_DESC = "Lzombie/characters/IsoPlayer;";
    private static final String CHARACTER_DESC = "Lzombie/characters/IsoGameCharacter;";
    private static final String WEAPON_DESC = "Lzombie/inventory/types/HandWeapon;";
    private static final String ON_KILLED_DESC = "(" + CHARACTER_DESC + WEAPON_DESC + "Z)V";
    private static final String KILLED_CALL_DESC = "(" + PLAYER_DESC + CHARACTER_DESC + WEAPON_DESC + ")V";

    @Override
    public boolean matches(String className) {
        return ISO_PLAYER.equals(className);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = ClassWriters.create(loader, reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals("onKilled") && descriptor.equals(ON_KILLED_DESC)) {
                    return new MethodVisitor(Opcodes.ASM9, original) {
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
                return original;
            }
        };
        reader.accept(visitor, 0);
        Debug.log("death message patch applied to " + className);
        return writer.toByteArray();
    }
}