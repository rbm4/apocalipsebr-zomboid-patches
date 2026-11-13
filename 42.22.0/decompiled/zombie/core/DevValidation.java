// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.core;

import generation.builders.validation.GenerationDependencyValidator;
import generation.builders.validation.TranslationKeyValidator;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import zombie.UsedFromLua;
import zombie.scripting.objects.Registry;

public class DevValidation {
    public static void generationIsolation() {
        if (Core.IS_DEV) {
            GenerationDependencyValidator.validate(DevValidation.class);
        }
    }

    public static void translationKey(String translationKey) {
        if (Core.IS_DEV) {
            TranslationKeyValidator.of(translationKey);
        }
    }

    @SafeVarargs
    public static <T> void translationKeys(Iterable<T> entries, Function<T, String>... translationKeys) {
        translationKeys(entries, (entry, validate) -> {
            for (Function<T, String> translationKey : translationKeys) {
                validate.accept(translationKey.apply(entry));
            }
        });
    }

    @SafeVarargs
    public static <E extends Enum<E>> void translationKeys(Class<E> entries, Function<E, String>... translationKeys) {
        if (Core.IS_DEV) {
            translationKeys(Arrays.asList(entries.getEnumConstants()), translationKeys);
        }
    }

    public static <T> void translationKeys(Iterable<T> entries, BiConsumer<T, Consumer<String>> translationKeys) {
        if (Core.IS_DEV) {
            for (T entry : entries) {
                translationKeys.accept(entry, TranslationKeyValidator::of);
            }
        }
    }

    public static void registries(Registry<Registry<?>> registries) {
        if (Core.IS_DEV) {
            registries.forEach(registry -> {
                if (registry.values().stream().anyMatch(Objects::isNull)) {
                    throw new IllegalStateException("Registry %s has null values".formatted(registries.getLocation((Registry<?>)registry)));
                }
            });
            registries.forEach(
                registry -> {
                    Optional<?> first = registry.values().stream().findFirst();
                    if (first.isEmpty()) {
                        throw new IllegalStateException("Registry %s was empty".formatted(registries.getLocation((Registry<?>)registry)));
                    } else {
                        Class<?> clazz = first.get().getClass();
                        if (Arrays.stream(clazz.getConstructors()).anyMatch(c -> Modifier.isPublic(c.getModifiers()))) {
                            throw new IllegalStateException(
                                "Registry %s is holding objects with a public constructor".formatted(registries.getLocation((Registry<?>)registry))
                            );
                        } else if (Arrays.stream(clazz.getDeclaredMethods())
                            .noneMatch(m -> m.getName().equals("registerBase") && Modifier.isPrivate(m.getModifiers()))) {
                            throw new IllegalStateException(
                                "Registry %s is missing a private registerBase, are we sure we set this up to be resettable?"
                                    .formatted(registries.getLocation((Registry<?>)registry))
                            );
                        } else {
                            try {
                                clazz.getDeclaredMethod("equals", Object.class);
                                throw new IllegalStateException(
                                    "Registry %s of %s should not override equals(Object)"
                                        .formatted(registries.getLocation((Registry<?>)registry), clazz.getName())
                                );
                            } catch (NoSuchMethodException var6) {
                                try {
                                    clazz.getDeclaredMethod("hashCode");
                                    throw new IllegalStateException(
                                        "Registry %s of %s should not override hashCode()"
                                            .formatted(registries.getLocation((Registry<?>)registry), clazz.getName())
                                    );
                                } catch (NoSuchMethodException var5) {
                                }
                            }
                        }
                    }
                }
            );
        }
    }

    public static void isAnnotated(Class<?> clazz) {
        if (Core.IS_DEV && !clazz.isAnnotationPresent(UsedFromLua.class) && !clazz.getName().startsWith("java.")) {
            throw new RuntimeException("Missing @%s annotation in %s".formatted(UsedFromLua.class.getSimpleName(), clazz.getName()));
        }
    }
}
