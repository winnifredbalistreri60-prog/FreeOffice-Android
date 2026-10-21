package ru.freeoffice.app;
public final class NativeBridge {
 static { System.loadLibrary("freeoffice"); }
 private NativeBridge() {}
 public static native String call(String requestJson);
}
