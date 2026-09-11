package com.puxadinho.patches.respawn;

import com.puxadinho.Patch;
import com.puxadinho.Debug;
import com.puxadinho.asm.ClassWriters;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class WorldRespawnPatch implements Patch {
    private static final String DESIGNATION_ZONE = "zombie/iso/areas/DesignationZone";
    private static final String VEHICLE_MANAGER = "zombie/vehicles/VehicleManager";
    private static final String BASE_VEHICLE = "zombie/vehicles/BaseVehicle";
    private static final String GUARD = "com/puxadinho/patches/respawn/RespawnGuard";
    private static final String VEHICLE_DESC = "Lzombie/vehicles/BaseVehicle;";

    @Override
    public boolean matches(String className) {
        return DESIGNATION_ZONE.equals(className) || VEHICLE_MANAGER.equals(className) || BASE_VEHICLE.equals(className);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = ClassWriters.create(loader, reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (DESIGNATION_ZONE.equals(className) && name.equals("update") && descriptor.equals("()V")) {
                    return tick(original, "tickZones");
                }
                if (VEHICLE_MANAGER.equals(className) && name.equals("serverUpdate") && descriptor.equals("()V")) {
                    return tick(original, "tickVehicles");
                }
                if (BASE_VEHICLE.equals(className) && name.equals("permanentlyRemove") && descriptor.equals("()V")) {
                    return removed(original);
                }
                return original;
            }
        };
        reader.accept(visitor, 0);
        Debug.log("respawn patch applied to " + className);
        return writer.toByteArray();
    }

    private static MethodVisitor tick(MethodVisitor mv, String method) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, method, "()V", false);
            }
        };
    }

    private static MethodVisitor removed(MethodVisitor mv) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                visitVarInsn(Opcodes.ALOAD, 0);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "removed", "(" + VEHICLE_DESC + ")V", false);
            }
        };
    }
}
