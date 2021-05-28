package com.github.forax.virtualbean;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LambdaCrackerTest {
  @Test
  public void extractLambdaInfo() {
    var consumer = (IntConsumer & Serializable) System.out::println;
    var lookup = MethodHandles.lookup();
    var lambdaInfo= LambdaCracker.extractLambdaInfo(lookup, consumer);
    assertAll(
        () -> assertEquals(LambdaCrackerTest.class.getName().replace('.', '/'), lambdaInfo.getCapturingClass()),

        () -> assertEquals(IntConsumer.class.getName().replace('.', '/'), lambdaInfo.getFunctionalInterfaceClass()),
        () -> assertEquals("accept", lambdaInfo.getFunctionalInterfaceMethodName()),
        () -> assertEquals("(I)V", lambdaInfo.getFunctionalInterfaceMethodSignature()),

        () -> assertEquals(5 /*invokeVirtual*/, lambdaInfo.getImplMethodKind()),
        () -> assertEquals(PrintStream.class.getName().replace('.', '/'), lambdaInfo.getImplClass()),
        () -> assertEquals("println", lambdaInfo.getImplMethodName()),
        () -> assertEquals("(I)V", lambdaInfo.getImplMethodSignature()),

        () -> assertEquals(1, lambdaInfo.getCapturedArgCount()),
        () -> assertEquals("(I)V", lambdaInfo.getInstantiatedMethodType())
    );
  }

  @Test
  public void extractMethodOrConstructorVirtual() {
    var consumer = (IntConsumer & Serializable) System.out::println;
    var lookup = MethodHandles.lookup();
    var lambdaInfo= LambdaCracker.extractLambdaInfo(lookup, consumer);
    var method = (Method) LambdaCracker.extractMethodOrConstructor(lookup, lambdaInfo);
    assertAll(
        () -> assertEquals(PrintStream.class, method.getDeclaringClass()),
        () -> assertEquals("println", method.getName()),
        () -> assertEquals(void.class, method.getReturnType()),
        () -> assertEquals(List.of(int.class), List.of(method.getParameterTypes()))
    );
  }

  @Test
  public void extractMethodOrConstructorStatic() {
    var function = (ToIntFunction<String> & Serializable) Integer::parseInt;
    var lookup = MethodHandles.lookup();
    var lambdaInfo= LambdaCracker.extractLambdaInfo(lookup, function);
    var method = (Method) LambdaCracker.extractMethodOrConstructor(lookup, lambdaInfo);
    assertAll(
        () -> assertEquals(Integer.class, method.getDeclaringClass()),
        () -> assertEquals("parseInt", method.getName()),
        () -> assertEquals(int.class, method.getReturnType()),
        () -> assertEquals(List.of(String.class), List.of(method.getParameterTypes()))
    );
  }

  @Test
  public void extractMethodOrConstructorInterfacePrivate() {
    interface Foo {
      private void bar() {
      }
    }
    var consumer = (Consumer<Foo> & Serializable) Foo::bar;
    var lookup = MethodHandles.lookup();
    var lambdaInfo= LambdaCracker.extractLambdaInfo(lookup, consumer);
    var method = (Method) LambdaCracker.extractMethodOrConstructor(lookup, lambdaInfo);
    assertAll(
        () -> assertEquals(Foo.class, method.getDeclaringClass()),
        () -> assertEquals("bar", method.getName()),
        () -> assertEquals(void.class, method.getReturnType()),
        () -> assertEquals(List.of(), List.of(method.getParameterTypes()))
    );
  }

  @Test
  public void extractMethodOrConstructorInterfaceStatic() {
    interface Foo {
      private static void bar() {
      }
    }
    var runnable = (Runnable & Serializable) Foo::bar;
    var lookup = MethodHandles.lookup();
    var lambdaInfo= LambdaCracker.extractLambdaInfo(lookup, runnable);
    var method = (Method) LambdaCracker.extractMethodOrConstructor(lookup, lambdaInfo);
    assertAll(
        () -> assertEquals(Foo.class, method.getDeclaringClass()),
        () -> assertEquals("bar", method.getName()),
        () -> assertEquals(void.class, method.getReturnType()),
        () -> assertEquals(List.of(), List.of(method.getParameterTypes()))
    );
  }
}