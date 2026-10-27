package androidx.test.runner.lifecycle;
public interface ActivityLifecycleMonitor { void addLifecycleCallback(ActivityLifecycleCallback c); void removeLifecycleCallback(ActivityLifecycleCallback c); java.util.Collection<android.app.Activity> getActivitiesInStage(Stage s); Stage getLifecycleStageOf(android.app.Activity a); }
