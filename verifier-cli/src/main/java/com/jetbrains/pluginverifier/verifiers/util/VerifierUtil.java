package com.jetbrains.pluginverifier.verifiers.util;

import com.google.common.base.Preconditions;
import com.intellij.structure.resolvers.Resolver;
import com.jetbrains.pluginverifier.location.ProblemLocation;
import com.jetbrains.pluginverifier.problems.FailedToReadClassProblem;
import com.jetbrains.pluginverifier.utils.FailUtil;
import com.jetbrains.pluginverifier.verifiers.VerificationContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;

public class VerifierUtil {
  public static boolean classExistsOrExternal(VerificationContext ctx, final Resolver resolver, final @NotNull String className) {
    FailUtil.assertTrue(!className.startsWith("["), className);
    FailUtil.assertTrue(!className.endsWith(";"), className);

    return ctx.getVerifierOptions().isExternalClass(className) || VerifierUtil.findClass(resolver, className, ctx) != null;
  }

  public static boolean isInterface(@NotNull ClassNode classNode) {
    return (classNode.access & Opcodes.ACC_INTERFACE) != 0;
  }

  private static String prepareArrayName(@NotNull final String className) {
    if (className.startsWith("[")) {
      int i = 1;
      while (i < className.length() && className.charAt(i) == '[') {
        i++;
      }

      return className.substring(i);
    }

    return className;
  }

  /**
   * Finds a class with the given name in the given resolver
   *
   * @param resolver  resolver to search in
   * @param className className in binary form
   * @param ctx       context to report a problem of missing class to
   * @return null if not found or exception occurs (in the last case FailedToReadClassProblem is reported)
   */
  @Nullable
  public static ClassNode findClass(@NotNull Resolver resolver, @NotNull String className, @NotNull VerificationContext ctx) {
    try {
      return resolver.findClass(className);
    } catch (IOException e) {
      ctx.registerProblem(new FailedToReadClassProblem(className, e.getLocalizedMessage()), ProblemLocation.fromPlugin(ctx.getPlugin().toString()));
      return null;
    }
  }

  @NotNull
  private static String withoutReturnType(@NotNull String descriptor) {
    int bracket = descriptor.lastIndexOf(')');
    Preconditions.checkArgument(bracket != -1);
    return descriptor.substring(0, bracket + 1);
  }


  @Nullable // return null for primitive types
  public static String extractClassNameFromDescr(@NotNull String descr) {
    descr = prepareArrayName(descr);

    if (isPrimitiveType(descr)) return null;

    if (descr.startsWith("L") && descr.endsWith(";")) {
      return descr.substring(1, descr.length() - 1);
    }

    return descr;
  }

  private static boolean isPrimitiveType(@NotNull final String type) {
    return "Z".equals(type) || "I".equals(type) || "J".equals(type) || "B".equals(type) ||
        "F".equals(type) || "S".equals(type) || "D".equals(type) || "C".equals(type);
  }

  public static boolean isFinal(final MethodNode superMethod) {
    return (superMethod.access & Opcodes.ACC_FINAL) != 0;
  }

  public static boolean isAbstract(@NotNull ClassNode clazz) {
    return (clazz.access & Opcodes.ACC_ABSTRACT) != 0;
  }

  public static boolean isPrivate(@NotNull MethodNode method) {
    return (method.access & Opcodes.ACC_PRIVATE) != 0;
  }

  private static boolean isPublic(@NotNull MethodNode method) {
    return (method.access & Opcodes.ACC_PUBLIC) != 0;
  }

  public static boolean isDefaultAccess(@NotNull MethodNode method) {
    return !isPublic(method) && !isProtected(method) && !isPrivate(method);
  }

  public static boolean isAbstract(@NotNull MethodNode method) {
    return (method.access & Opcodes.ACC_ABSTRACT) != 0;
  }

  public static boolean isProtected(@NotNull MethodNode method) {
    return (method.access & Opcodes.ACC_PROTECTED) != 0;
  }

  public static boolean isStatic(@NotNull MethodNode method) {
    return (method.access & Opcodes.ACC_STATIC) != 0;
  }
}
