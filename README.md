# Virtual Bean Factory
A simple API to compose bean behavior based on annotations.

A virtual bean is an interface that describes
- _properties_ as getters and setters
- _services_ as abstract or default methods

The `BeanFactory` API ([javadoc](https://jitpack.io/com/github/forax/virtual-bean/master-SNAPSHOT/javadoc/fr.umlv.virtualbean/com/github/forax/virtualbean/BeanFactory.html))
cleanly decouples the virtual bean definition,  from the semantics  of the annotation defined in terms
of implementors and interceptors.

This class offers capabilities similar to Spring, CDI or Guice but
decomposes the concept of interceptor into 2 different parts:
_interceptors_ that can be composed and _implementors_ that are unique for an abstract method (thus non composable).
In order to be composable, interceptors are less powerful than classical interceptors,
they return **void**, they can not change the arguments or the return value of a call, and
do not explicitly call each others.
Unlike Spring or CDI, there is no annotations with a predefined semantics, the semantics of an annotation
is fully defined by the code of the implementors and interceptors registered.

Conceptually, there are only three operations:
- `create(interface)` takes a virtual bean (the interface) and returns an instance
  of that interface with all the property initialize to their default values.
  
- `registerImplementor(annotation, implementor)`that registers a lambda that will be called
   to ask for an implementation (a method handle) for any abstract methods annotated
   by the annotation
  
- `registerInterceptor(annotation, filter, interceptor)` that register a lambda that
   will be called to get method handle that should run before (**PRE**) and after (**POST**)
   a method call.
  
There are several helper methods that allows to register `InvocationHandler`s and `Advice`s
instead of respectively `Implementors` and `Interceptor` that have an easier semantics
but are less performant because their API requires method arguments to be boxed in an array.

Also interceptors can be unregistered using `unregisterInterceptor(annotation, interceptor)`
allowing to dynamically add/remove pre and post code.

The beauty of all of this is that the clean separation between
the virtual bean, implementors and interceptors does not hinder performance,
but actually helps
- the implementation is fully lazy, if a method of the virtual bean is never
  called the runtime cost is zero
- implementors, invocation handlers, interceptors and advices are resolved once per call  
  and fully inlined
- if there are several interceptors for a call, there are called one after the
  other, and not one on top of the others, so no gigantic stracktraces

There are some examples below.  

## How to use it [![](https://jitpack.io/v/forax/virtual-bean.svg)](https://jitpack.io/#forax/beautiful_logger)

Get the latest binary distribution via [JitPack](https://jitpack.io/#forax/virtual-bean)

### Maven

```xml
  <repositories>
      <repository>
          <id>jitpack.io</id>
          <url>https://jitpack.io</url>
      </repository>
  </repositories>
  <dependency>
      <groupId>com.github.forax</groupId>
      <artifactId>virtual-bean</artifactId>
      <version>1.0</version>
  </dependency>
```

### Gradle

```gradle
  repositories {
      ...
      maven { url 'https://jitpack.io' }
  }
  dependencies {
      compile 'com.github.forax:virtual-bean:1.0'
  }
```


## All your parameter are belong to us

The virtual bean `HelloManager` defines a method `sayHello` annotated with
an annotation `@ParametersNonNull` to say that the parameter should not be null
```java
@Retention(RetentionPolicy.RUNTIME)
@interface ParametersNonNull { }

interface HelloManager {
  @ParametersNonNull
  default void sayHello(String text)  {
    System.out.println("hello " + text);
  }
}
```

The `BeanFactory` API let you define the semantics of the annotation `@ParametersNonNull`
using an advice with `registerAdvice()` and automatically provides an implementation
of any virtual beans with `create()`.

So we register an advice for the annotation class `ParametersNonNull` that calls
`Objects.requireNonNull` on all arguments and test by creating a `HelloManager`.
```java
  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    beanFactory.registerAdvice(ParametersNonNull.class, new Advice() {
      public void pre(Method method, Object bean, Object[] args) {
        System.out.println("pre " + Arrays.toString(args));
        for (int i = 0; i < args.length; i++) {
          Objects.requireNonNull(args[i], "argument " + i + " of " + method + " is null");
        }
      }

      public void post(Method method, Object bean, Object[] args) {
        System.out.println("post " + Arrays.toString(args));
      }
    });

    var helloManager = beanFactory.create(HelloManager.class);
    helloManager.sayHello("Bob");
    helloManager.sayHello(null);
  }
```

If you run this code, the last call to `sayHello` will throw a `NullPointException` because the argument is null. 
It's not the most efficient code tho, mostly because for each call, arguments are boxed in an array.
The example below, explains how to alleviate that issue.

The full code is available here: https://github.com/forax/virtual-bean/blob/master/src/test/java/com/github/forax/virtualbean/example/Example.java


## All your parameter are belong to us (the sequel)

We can improve the efficiency of the code above by using an _interceptor_ instead of an _advice_.
But this came at the price of having to figure out how the `java.lang.invoke` API really works.

The start of the code is the same, we create an annotation and using it on a method of a virtual bean,
but we also create a method handle (a function pointer) on `Objects.requireNonNull`
```java
  @Retention(RetentionPolicy.RUNTIME)
  @interface ParametersNonNull { }

  interface HelloManager {
    @ParametersNonNull
    default void sayHello(String text)  {
      System.out.println("hello " + text);
    }
  }
  
  private static final MethodHandle REQUIRE_NON_NULL;
  static {
    try {
      REQUIRE_NON_NULL = MethodHandles.lookup().findStatic(Objects.class,
          "requireNonNull", MethodType.methodType(Object.class, Object.class, String.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
```

We register an _interceptor_ that will be called only once per method call and return a method handle that
will check that if a parameter is an object, the method handle corresponding to `requireNonNull` must be called.
```java
  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    beanFactory.registerInterceptor(ParametersNonNull.class, (kind, method, type) -> {
      if (kind == POST) {
        return null;
      }
      var parameterTypes = method.getParameterTypes();
      var filters = new MethodHandle[parameterTypes.length];
      for(var i = 0; i < parameterTypes.length; i++) {
        var parameterType = parameterTypes[i];
        if (parameterType.isPrimitive()) {
          continue;
        }
        var requireNonNull = MethodHandles.insertArguments(REQUIRE_NON_NULL, 1,
            "argument " + i + " of " + method + " is null");
        var filter = requireNonNull.asType(MethodType.methodType(parameterType, parameterType));
        filters[i] = filter;
      }
      var empty = MethodHandles.empty(type);
      return MethodHandles.filterArguments(empty, 1, filters);
    });

    var helloManager = beanFactory.create(HelloManager.class);
    helloManager.sayHello("Bob");
    helloManager.sayHello(null);
  }
```

The behavior of this code is identical as the previous solution,
but it performs better because method arguments are not boxed anymore.

The full code is available here: https://github.com/forax/virtual-bean/blob/master/src/test/java/com/github/forax/virtualbean/example/Example2.java


## Invocation handler and object injection

A `BeanFactory` also provides implementations of abstract method by registering _implementors_.
We can use that to implement a simple dependency injection.

First we create an annotation `@Inject` and a class `Injector` that associate a class to a supplier
of instances of that class.
```java
  @Retention(RetentionPolicy.RUNTIME)
  @interface Inject { }

  static class Injector {
    private final HashMap<Class<?>, Supplier<?>> map = new HashMap<>();

    public <T> void bind(Class<T> type, Supplier<? extends T> supplier) {
      map.put(type, supplier);
    }
    public <T> T getInstance(Class<T> type) {
      return type.cast(map.get(type).get());
    }
  }
```

Then we register an _invocation handler_ that will be called when abstract methods annotated by `Inject` is called.`

In this example, each time the method `Clock.current()` is called, the _invocation_handler_ asks
the injector to supply an instance of `LocalTime`, calling `LocalTime.now()`.

```java
  public static void main(String[] arguments) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    var injector = new Injector();
    beanFactory.registerInvocationHandler(Inject.class
        (method, bean, args) -> injector.getInstance(method.getReturnType()));

    interface Clock {
      @Inject
      LocalTime current();
    }

    var clock = beanFactory.create(Clock.class);
    injector.bind(LocalTime.class, LocalTime::now);
    System.out.println(clock.current());
    System.out.println(clock.current());
  }
```

The full code is available here: https://github.com/forax/virtual-bean/blob/master/src/test/java/com/github/forax/virtualbean/example/Example3.java


## Dynamically add/remove an interceptor

The class `BeanFactory` API allows not only to add interceptors but also to remove them dynamically.
In that case, all method calls optimized by the VM using an interceptor will be trashed, and will be re-optimize
later with the new interceptors when the JIT will kick in again.

Here, we define an annotation `Log` that will log all calls of methods annotated with that annotation,
and a virtual bean `HelloManager` with a method `sayHello` annotated with `@Log`.

```java
  @Retention(RetentionPolicy.RUNTIME)
  @interface Log { }

  interface HelloManager {
    @Log
    default void sayHello(String text) {
      System.out.println("hello " + text);
    }
  }
```

The `main` shows an example of registering and then unregistering the logging interceptor
```java
  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    var interceptor = new Advice() {
      public void pre(Method method, Object bean, Object[] args) {
        System.out.println("enter " + method);
      }

      public void post(Method method, Object bean, Object[] args) {
        System.out.println("exit " + method);
      }
    }.asInterceptor();

    var helloManager = beanFactory.create(HelloManager.class);
    helloManager.sayHello("no log");

    beanFactory.registerInterceptor(Log.class, interceptor);
    helloManager.sayHello("with log");

    beanFactory.unregisterInterceptor(Log.class, interceptor);
    helloManager.sayHello("with no log anymore");
  }
```

The full code is available here: https://github.com/forax/virtual-bean/blob/master/src/test/java/com/github/forax/virtualbean/example/Example4.java


## There are more ...

There are more examples, all available here: https://github.com/forax/virtual-bean/blob/master/src/test/java/com/github/forax/virtualbean/example
