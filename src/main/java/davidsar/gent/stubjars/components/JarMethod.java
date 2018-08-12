/*
 *  Copyright 2018 David Sargent
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations under the License.
 */

package davidsar.gent.stubjars.components;

import davidsar.gent.stubjars.Utils;
import davidsar.gent.stubjars.components.expressions.CompileableExpression;
import davidsar.gent.stubjars.components.expressions.Expression;
import davidsar.gent.stubjars.components.expressions.Expressions;
import davidsar.gent.stubjars.components.expressions.StringExpression;
import davidsar.gent.stubjars.components.writer.Constants;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.Set;

public class JarMethod extends JarModifiers implements CompileableExpression {
    private static final Logger log = LoggerFactory.getLogger(JarMethod.class);
    private final JarClass<?> parentClazz;
    private final Method method;
    private String[] cachedParameters;

    JarMethod(@NotNull JarClass<?> parentClazz, @NotNull Method method) {
        this.parentClazz = parentClazz;
        this.method = method;
    }

    @Override
    protected int getModifiers() {
        return method.getModifiers();
    }

    private String name() {
        return method.getName();
    }

    @NotNull
    private String[] parameters() {
        if (cachedParameters != null) {
            return cachedParameters;
        }
        Parameter[] parameters = method.getParameters();
        String[] stringifiedParameters = Arrays.stream(parameters)
                .map(parameter -> JarType.toString(parameter.getParameterizedType()) + Constants.SPACE + parameter.getName())
                .toArray(String[]::new);

        if (method.isVarArgs()) {
            Parameter varArgsParameter = parameters[parameters.length - 1];
            Type parameterizedType = varArgsParameter.getParameterizedType();
            if (JarType.isArray(parameterizedType)) {
                if (parameterizedType instanceof GenericArrayType) {
                    stringifiedParameters[parameters.length - 1] =
                        JarType.toString(
                            ((GenericArrayType) parameterizedType).getGenericComponentType())
                            + "... " + varArgsParameter.getName();
                } else if (parameterizedType instanceof Class) {
                    stringifiedParameters[parameters.length - 1] =
                        JarType.toString(((Class) parameterizedType).getComponentType()) + "... "
                            + varArgsParameter.getName();
                }
            }
        }

        cachedParameters = stringifiedParameters;
        return stringifiedParameters;
    }

    private Expression buildMethod(boolean isEnumField) {
        if (!shouldWriteMethod(isEnumField)) {
            return StringExpression.EMPTY;
        }

        // Figure method signature
        final Expression security;
        if (parentClazz.isInterface()) {
            security = StringExpression.EMPTY;
        } else {
            security = Expressions.of(
                Expressions.fromString(security().getModifier()),
                Expressions.whenWithSpace(
                    security() != SecurityModifier.PACKAGE, Constants.SPACE
                )
            );
        }
        final Expression finalS = Expressions.whenWithSpace(isFinal(), "final");
        final Expression staticS = Expressions.whenWithSpace(isStatic(), "static");
        final Expression abstractS;
        if (parentClazz.isInterface() || isEnumField) {
            abstractS = StringExpression.EMPTY;
        } else {
            abstractS = Expressions.whenWithSpace(isAbstract(), "abstract");
        }

        final Expression returnTypeS = Expressions.forType(
            genericReturnType(), JarType.toExpression(genericReturnType())
        );
        final Expression nameS = Expressions.fromString(name());
        final Expression parametersS = Utils.arrayToListExpression(parameters(), Expressions::fromString);
        final Expression throwsS = Expressions.fromString(requiresThrowsSignature()
            ? " throws " + Utils.arrayToListExpression(throwsTypes(), JarType::toExpression)
            : Constants.EMPTY_STRING);
        final Expression genericS;
        TypeVariable<Method>[] typeParameters = typeParameters();
        genericS = Expressions.fromString(JarType.convertTypeParametersToString(typeParameters));

        // What should the method body be?
        final Expression stubMethod;
        final Type returnType = genericReturnType();
        if (returnType.equals(void.class)) {
            stubMethod = Expressions.emptyBlock();
        } else {
            stubMethod = Expressions
                .blockWith(Expressions.of(
                    Expressions.toSpaceAfter("return"),
                    Expressions.forType(
                        returnType, JarConstructor.castedDefaultType(returnType, parentClazz))
                ).asStatement());
        }

        // Finally, put all of the pieces together
        Expression methodHeader = Expressions.of(
            StringExpression.NEW_LINE,
            security,
            finalS,
            staticS,
            abstractS,
            genericS,
            returnTypeS,
            StringExpression.SPACE,
            nameS,
            Expressions.asParenthetical(parametersS),
            throwsS
        );

        if (parentClazz.isAnnotation() && hasDefaultValue()) {
            Expression methodBody = Expressions.of(
                Expressions.toSpaceAfter("default"),
                Expressions.forType(
                    defaultValue().getClass(), Value.defaultValueForType(defaultValue().getClass(),
                        true)
                ),
                StringExpression.SEMICOLON
            );

            return Expressions.of(methodHeader, StringExpression.SPACE, methodBody);
        } else if ((isAbstract() && !isEnumField) || (parentClazz.isInterface() && !isStatic())) {
            return Expressions.of(methodHeader, StringExpression.SEMICOLON);
        }

        return Expressions.of(methodHeader, StringExpression.SPACE, stubMethod);
    }

    private boolean shouldWriteMethod(boolean isEnumField) {
        // Skip create methods for these types of things, enum fields can't have static methods
        if ((isEnumField || parentClazz.isInterface()) && isStatic()) {
            return false;
        }

        // Check if the enum method we are about to write could actually exist
        if (isEnumField) {
            if (isFinal()) {
                return false;
            }

            Class<?> declaringClass = parentClazz.getClazz().getDeclaringClass();
            if (declaringClass != null) {
                try {
                    declaringClass.getDeclaredMethod(name(), parameterTypes());
                    return false;
                } catch (NoSuchMethodException ignored) {
                    // log.debug("method \"{}\" does not exist on enum \"{}\"", name(), parentClazz.name());
                }
            }
        }
        return true;
    }

    boolean isSynthetic() {
        return method.isSynthetic();
    }

    boolean shouldIncludeStaticMethod() {
        if (!isStatic()) {
            return shouldIncludeMethod();
        }

        if (parentClazz.isEnum()) {
            if (name().equals("values") || name().equals("valueOf")) {
                return false;
            }
        }

        Set<JarClass> jarClasses = parentClazz.allSuperClassesAndInterfaces();
        long count = jarClasses.stream()
                .filter(x -> x.hasMethod(method))
                .count();
        return count == 0 && shouldIncludeMethod();
    }

    private boolean shouldIncludeMethod() {
        for (Class<?> paramType : method.getParameterTypes()) {
            if (!JarClass.hasSafeName(paramType)) {
                return false;
            }
        }

        return JarClass.hasSafeName(method.getReturnType());
    }

    private Type genericReturnType() {
        return method.getGenericReturnType();
    }

    private TypeVariable<Method>[] typeParameters() {
        return method.getTypeParameters();
    }

    private Class<?>[] parameterTypes() {
        return method.getParameterTypes();
    }

    private boolean hasDefaultValue() {
        return defaultValue() != null;
    }

    private Object defaultValue() {
        return method.getDefaultValue();
    }

    private Type[] throwsTypes() {
        return method.getGenericExceptionTypes();
    }

    private boolean requiresThrowsSignature() {
        return throwsTypes().length > 0;
    }

    @Override
    public Expression compileToExpression() {
        return buildMethod(false);
    }

    Expression compileToExpression(boolean isEnumField) {
        return buildMethod(isEnumField);
    }
}
