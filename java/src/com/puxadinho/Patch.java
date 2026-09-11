package com.puxadinho;

public interface Patch {
    boolean matches(String className);

    byte[] transform(ClassLoader loader, String className, byte[] classfileBuffer) throws Exception;
}
