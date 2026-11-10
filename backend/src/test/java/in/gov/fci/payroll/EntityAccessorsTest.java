package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Every JPA entity (target DDL v4.0 tables) exposes its persisted state unchanged: each accessor
 * returns exactly the column value held, for a set value and for the opposite boolean. Behaviour
 * (state transitions, validation) is tested per entity; this covers the mapping surface the
 * repositories, services and views read.
 */
class EntityAccessorsTest {

    static Stream<Class<?>> entities() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        return scanner.findCandidateComponents("in.gov.fci.payroll").stream()
                .<Class<?>>map(
                        bd -> {
                            try {
                                return (Class<?>) Class.forName(bd.getBeanClassName());
                            } catch (ClassNotFoundException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                .sorted(java.util.Comparator.comparing(c -> c.getName()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entities")
    void everyAccessorReturnsTheHeldColumnValue(Class<?> type) throws Exception {
        for (boolean flag : new boolean[] {true, false}) {
            Object entity = instantiate(type);
            List<Field> fields = fields(type);
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                Object value = sample(f.getType(), f.getName(), i, flag);
                if (value == null && f.getType().isAnnotationPresent(Embeddable.class)) {
                    // A composite key: populate its columns too (getters read through it).
                    value = instantiate(f.getType());
                    List<Field> parts = fields(f.getType());
                    for (int j = 0; j < parts.size(); j++) {
                        Object part =
                                sample(
                                        parts.get(j).getType(),
                                        parts.get(j).getName(),
                                        100 + j,
                                        flag);
                        if (part != null) {
                            parts.get(j).set(value, part);
                        }
                    }
                }
                if (value != null) {
                    f.set(entity, value);
                }
            }
            int checked = 0;
            for (Method m : type.getMethods()) {
                if (m.getDeclaringClass() != type
                        || m.getParameterCount() != 0
                        || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Field f = fieldFor(type, m);
                Object owner = entity;
                if (f == null) {
                    // A column of an embedded composite key.
                    for (Field e : fields(type)) {
                        if (e.getType().isAnnotationPresent(Embeddable.class)
                                && e.get(entity) != null) {
                            Field part = fieldFor(e.getType(), m);
                            if (part != null) {
                                f = part;
                                owner = e.get(entity);
                            }
                        }
                    }
                }
                if (f == null) {
                    continue;
                }
                Object held = f.get(owner);
                Object returned = m.invoke(entity);
                if (m.getReturnType() == boolean.class && held instanceof Boolean b) {
                    assertThat(returned)
                            .as("%s.%s", type.getSimpleName(), m.getName())
                            .isEqualTo(b);
                } else if (m.getReturnType() == boolean.class && held instanceof String s) {
                    // Y/N columns read as booleans.
                    assertThat(returned)
                            .as("%s.%s", type.getSimpleName(), m.getName())
                            .isEqualTo("Y".equals(s));
                } else if (m.getReturnType().isAssignableFrom(f.getType())
                        || (m.getReturnType().isPrimitive() && held != null)) {
                    assertThat(returned)
                            .as("%s.%s", type.getSimpleName(), m.getName())
                            .isEqualTo(held);
                } else {
                    continue;
                }
                checked++;
            }
            assertThat(checked).as("accessors checked on %s", type.getSimpleName()).isPositive();
        }
    }

    private static Object instantiate(Class<?> type) throws Exception {
        Constructor<?> c = type.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            for (Field f : t.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                    f.setAccessible(true);
                    result.add(f);
                }
            }
        }
        return result;
    }

    /** The field an accessor reads: getX -> x, isX -> x (or the field isX itself). */
    private static Field fieldFor(Class<?> type, Method m) {
        String name = m.getName();
        List<String> candidates = new ArrayList<>();
        if (name.startsWith("get") && name.length() > 3) {
            candidates.add(decapitalise(name.substring(3)));
        } else if (name.startsWith("is") && name.length() > 2) {
            candidates.add(decapitalise(name.substring(2)));
            candidates.add(name);
        } else {
            return null;
        }
        for (String candidate : candidates) {
            for (Field f : fields(type)) {
                if (f.getName().equals(candidate)) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String decapitalise(String s) {
        return s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object sample(Class<?> t, String name, int i, boolean flag) {
        if (t == String.class) {
            return flag ? "V" + i : "N";
        }
        if (t == Long.class || t == long.class) {
            return 1000L + i + (flag ? 0 : 500);
        }
        if (t == Integer.class || t == int.class) {
            return 10 + i + (flag ? 0 : 50);
        }
        if (t == Short.class || t == short.class) {
            return (short) (3 + i);
        }
        if (t == Boolean.class || t == boolean.class) {
            return flag;
        }
        if (t == BigDecimal.class) {
            return new BigDecimal("12.34").add(BigDecimal.valueOf(i));
        }
        if (t == LocalDate.class) {
            return LocalDate.of(2026, 1, 1).plusDays(i);
        }
        if (t == LocalDateTime.class) {
            return LocalDateTime.of(2026, 1, 1, 10, 0).plusMinutes(i);
        }
        if (t == LocalTime.class) {
            return LocalTime.of(9, i % 60);
        }
        if (t == Instant.class) {
            return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i);
        }
        if (t == byte[].class) {
            return new byte[] {(byte) i, 1, 2};
        }
        if (t.isEnum()) {
            Object[] constants = t.getEnumConstants();
            return constants[flag ? 0 : constants.length - 1];
        }
        return null;
    }
}
