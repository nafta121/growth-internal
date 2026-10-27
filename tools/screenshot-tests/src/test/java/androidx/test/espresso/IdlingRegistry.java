package androidx.test.espresso;
import java.util.*;
public final class IdlingRegistry { private static final IdlingRegistry I = new IdlingRegistry(); public static IdlingRegistry getInstance() { return I; }
 public Collection<android.os.Looper> getLoopers() { return new ArrayList<android.os.Looper>(); } public Collection<IdlingResource> getResources() { return new ArrayList<IdlingResource>(); } }
