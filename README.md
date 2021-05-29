# Virtual Bean Factory
A simple API to compose bean behavior based on annotations.

A virtual bean is an interface that describes
- properties as getter and setter
- services as default method

The `BeanFactory` API cleanly decouples the virtual bean definition,
from the semantics  of the annotation defined in terms of advices and
interceptors that can be added and removed dynamically.

Conceptually, there are only two operations:
- `create(interface)` takes a virtual bean (the interface) and returns an instance
  of that interface with all the property initialize to their default values.
- `registerAdvice(annotation, filter, advice)` adds an advice (a method run before,
  and a method run after) to all methods that defines the annotation
  and for which the filter is true.
  
In fact, an advice defines two method handles that will be run before
and after a virtual bean methods. The method
`registerInterceptor(annotation, filter, interceptor)` which is used
by `registerAdvice()` allows to have a better control on those method
handles and can also be unregistered with
`unregisterInterceptor(annotation, interceptor)`.

The beauty of all of this is that the separation of concern between
the virtual bean, and the advice does not hinder performance, but actually helps
- the implementation is fully lazy, if a method of the virtual bean is never
  called the runtime cost is zero
- all the advices are resolved once per call (apart in case of unregistering)  
  and fully inlined
- if there are several interceptors for a call, there are called one after the
  other, and not one on top of the others, so no gigantic stracktraces
- speaking of stacktraces, all the plumbing is done by method handles
  so it does not appear in stacktraces at all.

Enough talk, let see some examples.  


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
The example 3, below, explains how to alleviate that issue.

The full code is available here: https://github.com/forax/virtual-bean/blob/master/src/test/java/com/github/forax/virtualbean/Example.java


## A dirty set example

A dirty set is the set of all bean instances that have been modified during
a transaction thus should be updated in the database at the end of that transaction.

We can write easily such behavior with the `BeanFactory` API.
First, we define an annotation `Entity` and a virtual bean with 2 properties
`name` and `email`. 

```java
@Retention(RetentionPolicy.RUNTIME)
@interface Entity { }

@Entity
interface UserBean {
  String getName();
  void setName(String name);
  String getEmail();
  void setEmail(String name);
}
```

then we define an annotation `Transactional` and a service `UserManager`
```java
@Retention(RetentionPolicy.RUNTIME)
@interface Transactional { }

interface UserManager {
  @Transactional
  default UserBean createUser(BeanFactory beanFactory, String name, String email) {
    var user = beanFactory.create(UserBean.class);
    user.setName(name);
    user.setEmail(email);
    EntityManager.current().update();
    return user;
  }
}
```

We also need a thread local entity manager that will store the dirty set,
here defined as a record, because it's just fewer keystrokes 

```java
  private static final ThreadLocal<EntityManager> ENTITY_MANAGERS =
        new ThreadLocal<>();

  record EntityManager(Set<Object> dirtySet) {
    public static EntityManager current() {
      return ENTITY_MANAGERS.get();
    }

    public void update() {
      System.out.println("dirtySet " + dirtySet);
      dirtySet.clear();
    }
  }
```

We can now define the semantics of `@Entity` that tracks the calls to the setters
and store the corresponding in the _dirty set_ of the entity manager and
`@Transactional` that creates an entity manager for the duration of the method.

```java
  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    beanFactory.registerAdvice(Entity.class, Metadata::isSetter, new Advice() {
      public void pre(Method method, Object bean, Object[] args) { }

      public void post(Method method, Object bean, Object[] args) {
        var entityManager = EntityManager.current();
        if (entityManager != null) {
          entityManager.dirtySet.add(bean);
        }
      }
    });
    beanFactory.registerAdvice(Transactional.class, new Advice() {
      public void pre(Method method, Object bean, Object[] args) {
        var dirtySet = Collections.newSetFromMap(new IdentityHashMap<>());
        var entityManager = new EntityManager(dirtySet);
        ENTITY_MANAGERS.set(entityManager);
      }

      public void post(Method method, Object bean, Object[] args) {
        ENTITY_MANAGERS.remove();
      }
    });
    
    var userManager = beanFactory.create(UserManager.class);
    var user = userManager.createUser(beanFactory, "Duke", "duke@openjdk.java.net");
    System.out.println("user " + user.getName() + " " + user.getEmail());
  }
```

The full code is available here: https://github.com/forax/virtual-bean/blob/master/src/test/java/com/github/forax/virtualbean/Example2.java


## All your parameter are belong to us (2)

The code above that does the runtime check using an advice can be improved
to be more efficient at the price of being a little more cryptic using
the package `java.lang.invoke`.

An advice box all the arguments in an array of objects, we can avoid that by using an interceptor.
So the start of the code is the same, we create an annotation and using it on a method of a virtual bean,
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
      REQUIRE_NON_NULL = MethodHandles.lookup().findStatic(Objects.class, "requireNonNull", MethodType.methodType(Object.class, Object.class, String.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
```

We register an interceptor that will be called only once per method call and return a method handle that
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
        var requireNonNull = MethodHandles.insertArguments(REQUIRE_NON_NULL, 1, "argument " + i + " of " + method + " is null");
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

The behavior of this code is identical, but it performs better because arguments are not boxed anymore.

The full code is available here: https://github.com/forax/virtual-bean/blob/master/src/test/java/com/github/forax/virtualbean/Example3.java


## Dynamically add/remove an interceptor

The class `BeanFactory` provides the capability to not only add an interceptor
but also to remove it dynamically. In that case, all codes optimized by the VM
will be trashed, and all the calls will be re-optimized later.

We define an annotation `Log` and a virtual bean `HelloManager` using
that annotation.

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

Then we can register or unregister the logging interceptor
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

The full code is available here: https://github.com/forax/virtual-bean/blob/master/src/test/java/com/github/forax/virtualbean/Example4.java
