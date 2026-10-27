package androidx.test.platform.app;
public final class InstrumentationRegistry { private static android.app.Instrumentation inst; public static void registerInstance(android.app.Instrumentation i, android.os.Bundle b) { inst = i; } public static android.app.Instrumentation getInstrumentation() { return inst; } }
