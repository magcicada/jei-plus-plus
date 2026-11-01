package com.lingmu0.JeiPlusPlusMod.client;

import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.gui.input.IClickableIngredientInternal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** Reflection bridges for JEI internals whose packages or method names changed. */
public final class JeiReflectionCompat {
    private JeiReflectionCompat() {
    }

    public static Object readField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.trySetAccessible(); return field.get(target); }
            catch (NoSuchFieldException ignored) { }
            catch (ReflectiveOperationException error) { return null; }
        }
        return null;
    }

    public static Object tryInvoke(Object target, String name) {
        try { return invokeNoArgs(target, name); }
        catch (ReflectiveOperationException | RuntimeException error) { return null; }
    }

    public static boolean hasMethod(Object target, String name) {
        for (Method method : target.getClass().getMethods()) if (method.getName().equals(name)) return true;
        return false;
    }

    public static Object clientConfigs() {
        return mezz.jei.common.Internal.getClientConfigs();
    }

    public static Object combineInputHandlers(String label, Object... handlers) {
        mezz.jei.common.input.IInputTarget[] targets = java.util.Arrays.stream(handlers)
            .map(mezz.jei.common.input.IInputTarget.class::cast)
            .toArray(mezz.jei.common.input.IInputTarget[]::new);
        return new mezz.jei.common.input.handlers.InputGroup(label, targets);
    }

    public static Object customInputHandler(InvocationHandler handler) {
        Class<?> type = mezz.jei.common.input.IInputTarget.class;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object result = handler.invoke(proxy, method, args);
            if (method.getName().equals("scroll")) return result instanceof Optional<?> o && o.isPresent();
            if (method.getName().equals("beginInput") && result instanceof Optional<?> o && o.isPresent()
                && !(o.get() instanceof mezz.jei.common.input.interaction.IInputInteraction)) {
                Object[] press = args.clone();
                return Optional.of(inputAction(release -> {
                    Object[] completed = press.clone();
                    completed[1] = release;
                    try { handler.invoke(proxy, method, completed); }
                    catch (RuntimeException | Error error) { throw error; }
                    catch (Throwable error) { throw new IllegalStateException(error); }
                }));
            }
            return result;
        });
    }

    public static Object proxyInputHandler(Supplier<Object> supplier) {
        return customInputHandler((proxy, method, args) -> invokeInputHandler(supplier.get(), method, args));
    }

    public static Object inputAction(java.util.function.Consumer<Object> action) {
        try {
            return loadType("mezz.jei.common.input.interaction.InputAction")
                .getConstructor(java.util.function.Consumer.class).newInstance(action);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    public static Optional<Object> prepareAction(Object input, java.util.function.Predicate<Object> click) {
        if (!click.test(input)) return Optional.empty();
        return Optional.of(inputAction(release -> click.test(release)));
    }

    public static Object inputArgument(Object[] args) { return args[args.length - 2]; }

    public static Object invokeInputHandler(Object target, Method method, Object[] args) throws Throwable {
        if (target == null) {
            throw new IllegalStateException("JEI returned a null input handler");
        }
        try {
            if (method.getName().equals("scroll")) {
                return ((mezz.jei.common.input.IInputTarget) target).scroll(
                    (double) args[0], (double) args[1], (double) args[2], (double) args[3])
                    ? Optional.of(target) : Optional.empty();
            }
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    public static IClickableIngredientInternal<?> clickableIngredient(
        Object element,
        BiPredicate<Double, Double> isMouseOver,
        boolean canClickToFocus,
        boolean allowCheat
    ) {
        Object mouseOverable = mouseOverable(isMouseOver);
        Class<?> type = loadType("mezz.jei.gui.input.ClickableIngredientInternal");
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 4
                && parameters[0].isInstance(element)
                && parameters[1].isInstance(mouseOverable)
                && parameters[2] == boolean.class
                && parameters[3] == boolean.class) {
                try {
                    return (IClickableIngredientInternal<?>) constructor.newInstance(
                        element,
                        mouseOverable,
                        allowCheat,
                        canClickToFocus
                    );
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Unable to create JEI clickable ingredient", exception);
                }
            }
        }
        throw new IllegalStateException("Unsupported JEI clickable ingredient constructor: " + type.getName());
    }

    public static BiPredicate<Double, Double> mouseOverPredicate(Object mouseOverable) {
        final Method method;
        try {
            // JEI often passes a generated lambda here. Resolve the method on
            // the public interface so invocation does not depend on the
            // generated implementation class being accessible.
            method = mezz.jei.common.input.IMouseOverable.class.getMethod(
                "containsMouse",
                double.class,
                double.class
            );
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to query JEI mouse-over target", exception);
        }

        return (mouseX, mouseY) -> {
            try {
                return (Boolean) method.invoke(mouseOverable, mouseX, mouseY);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Unable to query JEI mouse-over target", cause);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to query JEI mouse-over target", exception);
            }
        };
    }

    private static Object mouseOverable(BiPredicate<Double, Double> isMouseOver) {
        Class<?> mouseOverType = mezz.jei.common.input.IMouseOverable.class;
        return Proxy.newProxyInstance(
            mouseOverType.getClassLoader(),
            new Class<?>[]{mouseOverType},
            (proxy, method, args) -> switch (method.getName()) {
                case "containsMouse" -> isMouseOver.test(
                    ((Number) args[0]).doubleValue(),
                    ((Number) args[1]).doubleValue()
                );
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "JEI++ mouse-over predicate";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    public static InputConstants.Key inputKey(Object input) {
        return (InputConstants.Key) invokeNoArgsUnchecked(input, "getKey");
    }

    public static double inputMouseX(Object input) {
        return ((Number) invokeNoArgsUnchecked(input, "getMouseX")).doubleValue();
    }

    public static double inputMouseY(Object input) {
        return ((Number) invokeNoArgsUnchecked(input, "getMouseY")).doubleValue();
    }

    public static boolean isInputSimulated(Object input) {
        return (Boolean) invokeNoArgsUnchecked(input, "isSimulate");
    }

    public static Object invokeNoArgsUnchecked(Object target, String name) {
        try {
            return invokeNoArgs(target, name);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read JEI input " + name, exception);
        }
    }

    public static Object invokeNoArgs(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Class<?> loadType(String... names) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        for (String name : names) {
            try {
                return Class.forName(name, false, contextLoader);
            } catch (ClassNotFoundException ignored) {
                try {
                    return Class.forName(name, false, JeiReflectionCompat.class.getClassLoader());
                } catch (ClassNotFoundException ignoredAgain) {
                    // Continue with the alternate package name.
                }
            }
        }
        throw new IllegalStateException("None of the supported JEI classes are available: " + String.join(", ", names));
    }
}
