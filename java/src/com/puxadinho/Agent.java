package com.puxadinho;

import java.lang.instrument.Instrumentation;

public final class Agent {
    private Agent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        Debug.logStartupOnce();
        Debug.log("java agent starting (args=" + args + ")");
        instrumentation.addTransformer(new PatchTransformer(), true);
        Debug.log("transformer installed; patches=" + PatchTransformer.patchNames());
    }
}
