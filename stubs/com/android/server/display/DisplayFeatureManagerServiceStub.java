package com.android.server.display;
public interface DisplayFeatureManagerServiceStub {
    static DisplayFeatureManagerServiceStub getInstance() { return null; }
    default boolean isFullAodState(int displayId) { return false; }
}
