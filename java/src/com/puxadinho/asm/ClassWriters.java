package com.puxadinho.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

public final class ClassWriters {
    private ClassWriters() {
    }

    public static ClassWriter create(ClassLoader loader, ClassReader reader, int flags) {
        return new LoaderAwareClassWriter(reader, flags, loader);
    }

    private static final class LoaderAwareClassWriter extends ClassWriter {
        private final ClassLoader loader;

        LoaderAwareClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
                Class<?> c1 = Class.forName(type1.replace('/', '.'), false, cl);
                Class<?> c2 = Class.forName(type2.replace('/', '.'), false, cl);
                if (c1.isAssignableFrom(c2)) {
                    return type1;
                }
                if (c2.isAssignableFrom(c1)) {
                    return type2;
                }
                if (c1.isInterface() || c2.isInterface()) {
                    return "java/lang/Object";
                }
                do {
                    c1 = c1.getSuperclass();
                } while (!c1.isAssignableFrom(c2));
                return c1.getName().replace('.', '/');
            } catch (Throwable t) {
                return "java/lang/Object";
            }
        }
    }
}
