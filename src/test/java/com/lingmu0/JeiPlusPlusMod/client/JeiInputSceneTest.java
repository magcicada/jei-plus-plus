package com.lingmu0.JeiPlusPlusMod.client;

import mezz.jei.common.util.ImmutableRect2i;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression coverage using the real current-JEI scene/area classes, without launching Minecraft. */
public final class JeiInputSceneTest {
    public static void run(Object controls, Class<?> inputApi, Method begin, Object[] inputArgs) throws Exception {
        Class<?> mouseApi = Class.forName("mezz.jei.common.input.IMouseOverable");
        Class<?> areaApi = Class.forName("mezz.jei.gui.input.InputArea");
        Class<?> sceneApi = Class.forName("mezz.jei.gui.input.GuiInputScene");
        Hover recipes = new Hover(rect(0, 0, 100, 100), ImmutableRect2i.EMPTY);
        Hover ingredients = new Hover(rect(120, 40, 100, 120), rect(120, 20, 100, 18));
        Hover bookmarks = new Hover(rect(0, 160, 100, 20), rect(100, 160, 20, 20));
        Object recipe = area(areaApi, mouseApi, inputApi, "recipes", recipes, controls);
        Object ingredient = area(areaApi, mouseApi, inputApi, "ingredients", ingredients, controls);
        Object bookmark = area(areaApi, mouseApi, inputApi, "bookmarks", bookmarks, controls);
        List<Object> areas = List.of(recipe, ingredient, bookmark);
        Object scene = scene(sceneApi, areaApi, areas);

        // Reproduce the actual regression: native hit testing rejects both extra controls.
        check(nativeTarget(scene, 110, 170).isEmpty(), "native scene should exclude tree button");
        check(nativeTarget(scene, 130, 25).isEmpty(), "native scene should exclude reserved category row");
        check(select(areas, scene, 110, 170).orElseThrow() == bookmark, "tree button hit target");
        check(select(areas, scene, 130, 25).orElseThrow() == ingredient, "category row hit target");
        check(select(areas, scene, 500, 500).isEmpty(), "outside overlays must fall through");

        // Dispatch through the selected area's actual native control API, including release.
        Object selected = select(areas, scene, 110, 170).orElseThrow();
        Object target = areaApi.getMethod("controls").invoke(selected);
        Optional<?> accepted = (Optional<?>) begin.invoke(target, inputArgs);
        check(accepted.isPresent(), "selected tree target press");
        Class<?> interaction = Class.forName("mezz.jei.common.input.interaction.IInputInteraction");
        interaction.getMethod("complete", begin.getParameterTypes()[1]).invoke(accepted.orElseThrow(), new Object[]{null});
        Method scroll = inputApi.getMethod("scroll", double.class, double.class, double.class, double.class);
        check(Boolean.TRUE.equals(scroll.invoke(areaApi.getMethod("controls").invoke(ingredient), 130.0, 25.0, 0.0, -1.0)),
            "selected category target scroll");

        // Modal picker wins even where JEI's recipe/tooltip areas would otherwise come first.
        ingredients.modal = true;
        check(nativeTarget(scene, 50, 50).orElseThrow() == recipe, "native recipe target before modal override");
        check(select(areas, scene, 50, 50).orElseThrow() == ingredient, "picker must cover recipe input");
        check(select(areas, scene, 110, 170).orElseThrow() == ingredient, "picker must cover bookmarks");
        ingredients.modal = false;
        check(select(areas, scene, 50, 50).orElseThrow() == recipe, "closing picker restores native input");
        Hover tooltip = new Hover(rect(120, 20, 100, 18), ImmutableRect2i.EMPTY);
        Object front = area(areaApi, mouseApi, inputApi, "tooltip", tooltip, controls);
        List<Object> withTooltip = List.of(front, recipe, ingredient, bookmark);
        Object tooltipScene = scene(sceneApi, areaApi, withTooltip);
        check(select(withTooltip, tooltipScene, 130, 25).orElseThrow() == front, "preserve native foreground priority");
        ingredients.enabled = false;
        check(select(areas, scene, 130, 25).isEmpty(), "disabled category feature must not claim events");

        check(JeiInputAreaCompat.insideBand(40, 25, rect(10, 20, 10, 10), rect(90, 20, 10, 10)), "page number gap");
        check(!JeiInputAreaCompat.insideBand(40, 35, rect(10, 20, 10, 10), rect(90, 20, 10, 10)), "outside navigation row");
        check(!JeiInputAreaCompat.insideBand(0, 0, ImmutableRect2i.EMPTY, ImmutableRect2i.EMPTY), "hidden row");
        Navigation gui = new Navigation();
        check(JeiInputAreaCompat.scrollRecipeNavigation(gui, 40, 25, -1), "category row wheel");
        check(gui.logic.categories.get() == 1, "category direction/count");
        check(JeiInputAreaCompat.scrollRecipeNavigation(gui, 40, 45, 1), "recipe page row wheel");
        check(gui.logic.pages.get() == -1, "page direction/count");
        check(!JeiInputAreaCompat.scrollRecipeNavigation(gui, 40, 120, -1), "recipe body keeps native scrolling");
        check(!JeiInputAreaCompat.scrollRecipeNavigation(gui, 40, 25, 0), "zero delta");
        testWheelPriority(inputApi);
        System.out.println("JEI scene regression checks passed: extra hit regions, modal priority, press/release and wheel navigation");
    }

    /** Use JEI's real short-circuiting InputGroup, including its formerly greedy page target. */
    private static void testWheelPriority(Class<?> inputApi) throws Exception {
        Navigation gui = new Navigation();
        AtomicInteger genericCalls = new AtomicInteger();
        Object priority = wheelTarget((x, y, dy) -> JeiInputAreaCompat.scrollRecipeNavigation(gui, x, y, dy));
        Object generic = wheelTarget((x, y, dy) -> { genericCalls.incrementAndGet(); return true; });
        Object lateTabs = wheelTarget((x, y, dy) -> JeiInputAreaCompat.scrollRecipeTabs(gui.recipeGuiTabs, x, y, dy));
        Method scroll = inputApi.getMethod("scroll", double.class, double.class, double.class, double.class);
        Object broken = JeiReflectionCompat.combineInputHandlers("native order without early tab routing", generic, lateTabs);
        scroll.invoke(broken, 40.0, 65.0, 0.0, -1.0);
        check(genericCalls.get() == 1 && gui.recipeGuiTabs.pages.get() == 0, "reproduce tab wheel consumed before late target");
        genericCalls.set(0);
        Object fixed = JeiReflectionCompat.combineInputHandlers("early category navigation", priority, generic, lateTabs);
        check(Boolean.TRUE.equals(scroll.invoke(fixed, 40.0, 65.0, 0.0, -1.0)), "category page-number band");
        check(gui.recipeGuiTabs.pages.get() == 1 && genericCalls.get() == 0, "page-number wheel must run before generic handler");
        scroll.invoke(fixed, 40.0, 65.0, 0.0, 1.0);
        check(gui.recipeGuiTabs.pages.get() == 0, "category page-number reverse direction");
        scroll.invoke(fixed, 40.0, 85.0, 0.0, -1.0);
        check(gui.logic.categories.get() == 1 && genericCalls.get() == 0, "icon row changes category, not recipe page");
        scroll.invoke(fixed, 40.0, 85.0, 0.0, 1.0);
        check(gui.logic.categories.get() == 0, "icon row reverse direction");
        scroll.invoke(fixed, 40.0, 120.0, 0.0, -1.0);
        check(genericCalls.get() == 1, "recipe body still uses native scrolling");
    }

    @FunctionalInterface
    private interface WheelAction { boolean scroll(double x, double y, double dy); }

    private static Object wheelTarget(WheelAction action) {
        return JeiReflectionCompat.customInputHandler((proxy, method, args) -> switch (method.getName()) {
            case "toString" -> "wheel regression target";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "resetInput" -> null;
            case "scroll" -> action.scroll((double) args[0], (double) args[1], (double) args[3])
                ? Optional.of(proxy) : Optional.empty();
            default -> Optional.empty();
        });
    }

    private static ImmutableRect2i rect(int x, int y, int w, int h) { return new ImmutableRect2i(x, y, w, h); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static Object area(Class<?> areaApi, Class<?> mouseApi, Class<?> inputApi,
                               String name, Hover hover, Object controls) throws Exception {
        Object source = Proxy.newProxyInstance(JeiInputSceneTest.class.getClassLoader(),
            new Class<?>[]{mouseApi, JeiExtraInputArea.class}, (proxy, method, args) -> switch (method.getName()) {
                case "isMouseOver", "containsMouse" -> hover.nativeArea.contains((double) args[0], (double) args[1]);
                case "jeiPlusPlus$containsExtraInput" -> hover.enabled && (hover.modal || hover.extraArea.contains((double) args[0], (double) args[1]));
                case "jeiPlusPlus$isModalInput" -> hover.modal;
                case "toString" -> name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError(method.toString());
            });
        Object builder = areaApi.getMethod("builder", String.class, mouseApi).invoke(null, name, source);
        builder.getClass().getMethod("controls", inputApi).invoke(builder, controls);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private static Object scene(Class<?> sceneApi, Class<?> areaApi, List<Object> areas) throws Exception {
        Object array = Array.newInstance(areaApi, areas.size());
        for (int i = 0; i < areas.size(); i++) Array.set(array, i, areas.get(i));
        Class<?> focusApi = Class.forName("mezz.jei.gui.input.focus.GuiKeyboardFocus");
        Object keyboardFocus = focusApi.getConstructor().newInstance();
        return sceneApi.getConstructor(focusApi, array.getClass()).newInstance(keyboardFocus, array);
    }

    private static Optional<?> nativeTarget(Object scene, double x, double y) throws Exception {
        return (Optional<?>) scene.getClass().getMethod("getTarget", double.class, double.class).invoke(scene, x, y);
    }

    private static Optional<?> select(List<Object> areas, Object scene, double x, double y) throws Exception {
        return JeiInputAreaCompat.selectTarget(areas, nativeTarget(scene, x, y), x, y);
    }

    private static final class Hover {
        final ImmutableRect2i nativeArea, extraArea;
        boolean modal, enabled = true;
        Hover(ImmutableRect2i nativeArea, ImmutableRect2i extraArea) { this.nativeArea = nativeArea; this.extraArea = extraArea; }
    }

    public record Button(ImmutableRect2i bounds) { public ImmutableRect2i getArea() { return bounds; } }
    public static final class Navigation {
        final Button previousRecipeCategory = new Button(rect(10, 20, 10, 10));
        final Button nextRecipeCategory = new Button(rect(90, 20, 10, 10));
        final Button previousPage = new Button(rect(10, 40, 10, 10));
        final Button nextPage = new Button(rect(90, 40, 10, 10));
        final Logic logic = new Logic();
        final Tabs recipeGuiTabs = new Tabs(logic);
    }
    public static final class Tabs {
        final ImmutableRect2i area = rect(10, 80, 90, 18);
        final Logic recipeGuiLogic;
        final PageNavigation pageNavigation = new PageNavigation();
        final AtomicInteger pages = new AtomicInteger();
        Tabs(Logic logic) { recipeGuiLogic = logic; }
        public boolean nextPage() { pages.incrementAndGet(); return true; }
        public boolean previousPage() { pages.decrementAndGet(); return true; }
    }
    public static final class PageNavigation {
        public ImmutableRect2i getBackButtonArea() { return rect(10, 60, 10, 10); }
        public ImmutableRect2i getNextButtonArea() { return rect(90, 60, 10, 10); }
    }
    public static final class Logic {
        final AtomicInteger categories = new AtomicInteger(), pages = new AtomicInteger();
        public boolean nextRecipeCategory() { categories.incrementAndGet(); return true; }
        public boolean previousRecipeCategory() { categories.decrementAndGet(); return true; }
        public boolean nextPage() { pages.incrementAndGet(); return true; }
        public boolean previousPage() { pages.decrementAndGet(); return true; }
    }
}
