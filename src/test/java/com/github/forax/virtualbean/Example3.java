package com.github.forax.virtualbean;

import com.github.forax.virtualbean.BeanFactory.Advice;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;

public class Example3 {
  @Retention(RetentionPolicy.RUNTIME)
  @interface Log { }

  interface HelloManager {
    @Log
    default void sayHello(String text) {
      System.out.println("hello " + text);
    }
  }

  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    var interceptor = new Advice() {
      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        System.out.println("enter " + method);
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        System.out.println("exit " + method);
      }
    }.asInterceptor();

    var helloManager = beanFactory.proxy(HelloManager.class);
    helloManager.sayHello("no log");

    beanFactory.registerInterceptor(Log.class, __ -> true, interceptor);
    helloManager.sayHello("with log");

    beanFactory.unregisterInterceptor(Log.class, interceptor);
    helloManager.sayHello("with no log anymore");
  }
}
