package me.davidsargent.stubjars.components;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.stream.Collectors;

public class JarType {
    private final Type type;
    private final boolean isGeneric;

    public JarType(@NotNull Type type) {
        this.type = type;
        isGeneric = type instanceof ParameterizedType;
    }

    private boolean isGeneric() {
        return isGeneric;
    }

    @Nullable
    public Class<?> getOwnerClass() {
        return isGeneric() ? ((Class<?>) getParameterizedType().getOwnerType()) : (Class<?>) type;
    }

    public Type getRawType() {
        return isGeneric() ? getParameterizedType().getRawType() : type;
    }

    @NotNull
    private ParameterizedType getParameterizedType() {
        return (ParameterizedType) type;
    }

    @NotNull
    @Override
    public String toString() {
        return toString(type);
    }

    @NotNull
    public static String toString(@NotNull Type type) {
        if (type instanceof Class) {
            return JarClass.safeFullNameForClass((Class<?>) type);
        } else if (type instanceof ParameterizedType) {
            StringBuilder builder = new StringBuilder();
            ParameterizedType pType = (ParameterizedType) type;
            if (pType.getOwnerType() != null) {
                Type ownerType = pType.getOwnerType();
                if (ownerType instanceof Class) {
                    builder.append(JarClass.safeFullNameForClass((Class<?>) pType.getRawType()));
                } else if (ownerType instanceof ParameterizedType) {
                    builder.append(toString(ownerType));
                    builder.append(".");
                    Class<?> rawTypeOfOwner = (Class<?>) ((ParameterizedType) pType.getOwnerType()).getRawType();
                    String rawType = ((Class<?>) pType.getRawType()).getName()
                            .replace(rawTypeOfOwner.getName() + "$", "");
                    builder.append(rawType);
                } else {
                    throw new UnsupportedOperationException(type.getClass().getName());
                }
            } else {
                builder.append(JarClass.safeFullNameForClass((Class<?>) pType.getRawType()));
            }

            Type[] actualTypeArguments = pType.getActualTypeArguments();
            if (actualTypeArguments != null && actualTypeArguments.length > 0) {
                builder.append('<');
                String collect = Arrays.stream(actualTypeArguments).map(typeArg -> {
                    if (typeArg instanceof Class ||
                            typeArg instanceof ParameterizedType ||
                            typeArg instanceof TypeVariable ||
                            typeArg instanceof GenericArrayType ||
                            typeArg instanceof WildcardType) return toString(typeArg);
                    throw new UnsupportedOperationException(typeArg.getClass().getName());
                }).collect(Collectors.joining(", "));
                builder.append(collect).append('>');
            }

            return builder.toString();
        } else if (type instanceof TypeVariable) {
            TypeVariable tType = (TypeVariable) type;
//            if (tType.getBounds()[0] == Object.class) {
//                return tType.getName();
//            } else {
//                return tType.getName() + " extends " + toString(tType.getBounds()[0]);
//            }

            return tType.getName();
        } else if (type instanceof GenericArrayType) {
            return toString(((GenericArrayType) type).getGenericComponentType()) + "[]";
        } else if (type instanceof WildcardType) {
            WildcardType wType = (WildcardType) type;
            if (wType.getLowerBounds() != null && wType.getLowerBounds().length > 0) {
                return "? super " + toString(wType.getLowerBounds()[0]);
            } else if (wType.getUpperBounds()[0] == Object.class) {
                return "?";
            } else {
                return "? extends " + toString(wType.getUpperBounds()[0]);
            }
        }


        // debug return type.toString();
        throw new UnsupportedOperationException(type.getClass().getName());
    }
}
