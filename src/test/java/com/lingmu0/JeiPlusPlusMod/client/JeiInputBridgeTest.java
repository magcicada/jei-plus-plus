package com.lingmu0.JeiPlusPlusMod.client;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class JeiInputBridgeTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Object target = JeiReflectionCompat.customInputHandler((proxy, method, values) -> switch (method.getName()) {
            case "toString" -> "JEI++ test input";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == values[0];
            case "resetInput" -> null;
            case "beginInput" -> { calls.incrementAndGet(); yield Optional.of(proxy); }
            case "scroll" -> Optional.of(proxy);
            default -> Optional.empty();
        });
        Class<?> api = target.getClass().getInterfaces()[0];
        boolean modern = api.getName().endsWith("IInputTarget");
        Method begin = Arrays.stream(api.getMethods()).filter(m -> m.getName().equals("beginInput")).findFirst().orElseThrow();
        Object[] inputArgs = new Object[begin.getParameterCount()];
        if (!modern) inputArgs[inputArgs.length - 2] = legacyInput(begin.getParameterTypes()[inputArgs.length - 2]);
        Optional<?> accepted = (Optional<?>) begin.invoke(target, inputArgs);
        if (accepted.isEmpty() || calls.get() != 1) throw new AssertionError("press dispatch");
        if (modern) {
            Object interaction = accepted.get();
            Class<?> action = Class.forName("mezz.jei.common.input.interaction.IInputInteraction");
            Method complete = Arrays.stream(action.getMethods()).filter(m -> m.getName().equals("complete")).findFirst().orElseThrow();
            complete.invoke(interaction, new Object[]{null});
            if (calls.get() != 2) throw new AssertionError("release dispatch");
        }
        Object group = JeiReflectionCompat.combineInputHandlers("test", target);
        Object proxy = JeiReflectionCompat.proxyInputHandler(() -> group);
        if (((Optional<?>) begin.invoke(proxy, inputArgs)).isEmpty()) throw new AssertionError("group/delegate dispatch");
        for (Method method : api.getMethods()) {
            if (method.getName().equals("scroll")) {
                Object[] values = new Object[method.getParameterCount()];
                Arrays.fill(values, 1.0);
                Object value = method.invoke(proxy, values);
                if (modern ? !Boolean.TRUE.equals(value) : ((Optional<?>) value).isEmpty()) throw new AssertionError("scroll dispatch");
            }
        }
        if (modern) JeiInputSceneTest.run(target, api, begin, inputArgs);
        System.out.println("JEI input compatibility checks passed against " + api.getName());
    }

    /** Use a real legacy IMMEDIATE event; CombinedInputHandler reads its phase. */
    private static Object legacyInput(Class<?> type) throws Exception {
        if (Modifier.isAbstract(type.getModifiers())) {
            type = Class.forName(type.getPackageName() + ".MouseUserInput");
        }
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            Object[] values = new Object[types.length];
            boolean phase = false;
            for (int i = 0; i < types.length; i++) {
                if (types[i].isEnum()) { values[i] = types[i].getField("IMMEDIATE").get(null); phase = true; }
                else if (types[i] == int.class) values[i] = 0;
                else if (types[i] == double.class) values[i] = 0.0;
                else if (types[i] == boolean.class) values[i] = false;
                else if (types[i].isRecord()) values[i] = recordValue(types[i]);
            }
            if (phase) return constructor.newInstance(values);
        }
        throw new AssertionError("No supported legacy input constructor: " + type);
    }

    private static Object recordValue(Class<?> type) throws Exception {
        Class<?>[] types = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) values[i] = 0;
            else if (types[i] == double.class) values[i] = 0.0;
            else if (types[i] == boolean.class) values[i] = false;
            else if (types[i].isRecord()) values[i] = recordValue(types[i]);
        }
        return type.getConstructor(types).newInstance(values);
    }
}
