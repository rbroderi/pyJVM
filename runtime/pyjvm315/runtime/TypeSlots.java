package pyjvm315.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registered per-type slot tables (CPython's tp_* slots). Each slot is resolved from the
 * receiver's Java class: the first registered entry whose type accepts the receiver wins,
 * so registration order is the priority order. Results are cached per class.
 */
final class TypeSlots {
    private TypeSlots() {}

    /** tp_callmethod: obj.name(*args). */
    interface CallMethod { Object call(Object self, String name, Object[] args); }

    private record Entry(Class<?> type, CallMethod slot) {}
    private static final ArrayList<Entry> CALL_METHOD_ENTRIES = new ArrayList<>();
    private static void add(Class<?> type, CallMethod slot) { CALL_METHOD_ENTRIES.add(new Entry(type, slot)); }

    static {
        add(PyComplex.class, PyComplexMethods::call);
        add(PySuper.class, PySuperMethods::call);
        add(PyBuiltinType.class, PyBuiltinTypeMethods::call);
        add(PyClass.class, PyClassMethods::call);
        add(PyAsyncGenerator.class, PyAsyncGeneratorMethods::call);
        add(PyCoroutine.class, PyCoroutineMethods::call);
        add(PyGenerator.class, PyGeneratorMethods::call);
        add(PyModule.class, PyModuleMethods::call);
        add(PyBytes.class, PyBytesMethods::call);
        add(PyByteArray.class, PyByteArrayMethods::call);
        add(PyMemoryView.class, PyMemoryViewMethods::call);
        add(String.class, StringMethods::call);
        add(Long.class, IntMethods::call);
        add(java.math.BigInteger.class, IntMethods::call);
        add(Boolean.class, IntMethods::call);
        add(List.class, ListMethods::call);
        add(Map.class, MapMethods::call);
        add(Set.class, SetMethods::call);
        add(PyInstance.class, PyInstanceMethods::call);
    }

    private static final ClassValue<CallMethod> CALL_METHOD = new ClassValue<>() {
        @Override protected CallMethod computeValue(Class<?> type) {
            for (Entry e : CALL_METHOD_ENTRIES) if (e.type.isAssignableFrom(type)) return e.slot;
            return null;
        }
    };

    static CallMethod callMethodSlot(Object receiver) {
        return receiver == null ? null : CALL_METHOD.get(receiver.getClass());
    }
}
