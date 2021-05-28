# Virtual Bean Factory
A simple API to compose bean behavior based on annotations.

A virtual bean is an interface that describes
- properties as getter and setter
- services as default method

The `BeanFactory` API cleanly decouples the virtual bean definition,
from the semantics  of the annotation defined in terms of advices and
interceptors that can be added and removed dynamically.

Conceptually, there are only two operations:
- `proxy(interface)` takes a virtual bean (the interface) and returns an instance
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

## A bound check example

The virtual bean `Service` defines a method `foo`
```java
interface Service {
  default void foo(@BoundChecks(min = 0, max = 10) int value)  {
    System.out.println("foo " + value);
  }
}
```

The service `foo` uses the annotation `BoundChecks` defined that way
```java
@Retention(RetentionPolicy.RUNTIME)
@interface BoundChecks {
  int max();
  int min();
}
```

The `BeanFactory` API let you define the semantics of the annotation `@BoundChecks`
using an advice with `registerAdvice()` and automatically provides an implementation
of any virtual beans with `proxy()`.

Here is an example that prints the arguments before and after a method annotated with
`@BoundChacks`.``
```java
  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    beanFactory.registerAdvice(BoundChecks.class, new Advice() {
      public void pre(Method method, Object proxy, Object[] args) {
        System.out.println("pre " + Arrays.toString(args));
        //TODO
      }
      
      public void post(Method method, Object proxy, Object[] args) {
        System.out.println("post " + Arrays.toString(args));
      }
    });

    var service = beanFactory.proxy(Service.class);
    service.foo(3);
    service.foo(-1);
  }
```

Now we can provide a real implementation for the method `pre`
```java
      public void pre(Method method, Object proxy, Object[] args) {
        System.out.println("pre " + Arrays.toString(args));
        var parameterAnnotations = method.getParameterAnnotations();
        for(var i = 0; i < args.length; i++) {
          for(var annotation:  parameterAnnotations[i]) {
            if (annotation instanceof BoundChecks boundChecks) {
              var value = (int) args[i];
              if (value < boundChecks.min() || value > boundChecks.max()) {
                throw new IllegalArgumentException("invalid value " + value);
              }
            }
          }
        }
      }
```

The full code is available here:


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
    var user = beanFactory.proxy(UserBean.class);
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
      public void pre(Method method, Object proxy, Object[] args) { }

      public void post(Method method, Object proxy, Object[] args) {
        var entityManager = EntityManager.current();
        if (entityManager != null) {
          entityManager.dirtySet.add(proxy);
        }
      }
    });
    beanFactory.registerAdvice(Transactional.class, new Advice() {
      public void pre(Method method, Object proxy, Object[] args) {
        var dirtySet = Collections.newSetFromMap(new IdentityHashMap<>());
        var entityManager = new EntityManager(dirtySet);
        ENTITY_MANAGERS.set(entityManager);
      }

      public void post(Method method, Object proxy, Object[] args) {
        ENTITY_MANAGERS.remove();
      }
    });
    
    var userManager = beanFactory.proxy(UserManager.class);
    var user = userManager.createUser(beanFactory, "Duke", "duke@openjdk.java.net");
    System.out.println("user " + user.getName() + " " + user.getEmail());
  }
```

The full code is available here:


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
      public void pre(Method method, Object proxy, Object[] args) {
        System.out.println("enter " + method);
      }

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
```

The full code is available here:
