package thepusher;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Pushes values into objects. Supports fields and constructors.
 * <p/>
 *
 * @author Sam Pullara
 * @author John Beatty
 *         Date: Sep 27, 2010
 *         Time: 1:14:48 PM
 */
@SuppressWarnings({"unchecked"})
public class PusherBase<E> implements Pusher<E> {
  private static Object NULL = new Object();
  private final static Logger logger = Logger.getLogger(Pusher.class.getName());
  private final Class<? extends Annotation> pushAnnotation;
  private final Method valueMethod;

  private PusherBase(Class<E> bindingEnumeration, Class<? extends Annotation> pushAnnotation) {
    this.pushAnnotation = pushAnnotation;
    try {
      valueMethod = pushAnnotation.getMethod("value");
      if (valueMethod.getReturnType() != bindingEnumeration) {
        throw new PusherException("Return type of value() method must be the enumeration type");
      }
      Target target = pushAnnotation.getAnnotation(Target.class);
      if (target == null) {
        throw new PusherException("No Target annotation on annotation, must target parameters and/or fields");
      }
      boolean parameterOrField = false;
      LOOP:
      for (ElementType etype : target.value()) {
        switch (etype) {
          case FIELD:
          case PARAMETER:
            parameterOrField = true;
            break LOOP;
        }
      }
      if (!parameterOrField) {
        throw new PusherException("Annotation must target parameters and/or fields");
      }
      Retention retention = pushAnnotation.getAnnotation(Retention.class);
      if (retention == null) {
        throw new PusherException("No retention policy set on annotation, you must set it to RUNTIME");
      } else if (retention.value() != RetentionPolicy.RUNTIME) {
        throw new PusherException("Annotation retention policy must be set to RUNTIME");
      }
    } catch (NoSuchMethodException e) {
      throw new PusherException("Annotation missing value() method", e);
    }
  }

  private Map<E, Class> classBindings = new ConcurrentHashMap<E, Class>();
  private Map<E, Object> instanceBindings = new ConcurrentHashMap<E, Object>();

  @Override
  public <T> void bindClass(E binding, Class<T> type) {
    classBindings.put(binding, type);
  }

  @Override
  public <T> T create(Class<T> type) {
    T o = instantiate(type);
    push(o);
    return o;
  }

  private <T> T instantiate(Class<T> type) {
    try {
      T o = null;
      Constructor<?>[] declaredConstructors = type.getDeclaredConstructors();
      Object[] parameterValues = null;
      for (Constructor constructor : declaredConstructors) {
        constructor.setAccessible(true);
        Annotation[][] parameterAnnotations = constructor.getParameterAnnotations();
        int length = parameterAnnotations.length;
        if (length == 0) continue;
        for (int i = 0; i < length; i++) {
          Annotation foundAnnotation = null;
          for (Annotation parameterAnnotation : parameterAnnotations[i]) {
            if (parameterAnnotation.annotationType().equals(pushAnnotation)) {
              foundAnnotation = parameterAnnotation;
              break;
            }
          }
          if (foundAnnotation == null) {
            if (i == 0) {
              // This constructor is not a candidate
              break;
            }
            throw new PusherException("All parameters of constructor must be annotated: " + constructor);
          } else if (i == 0) {
            if (parameterValues != null) {
              throw new PusherException("Already found a valid constructor");
            }
            parameterValues = new Object[length];
          }
          E parameterBinding = (E) valueMethod.invoke(foundAnnotation);
          Class removed = classBindings.remove(parameterBinding);
          if (removed != null) {
            rebind(parameterBinding, instantiate(removed));
          }
          if (instanceBindings.containsKey(parameterBinding)) {
            parameterValues[i] = get(parameterBinding);
            if (removed != null) {
              push(parameterValues[i]);
            }
          } else {
            throw new PusherException("Binding not bound: " + parameterBinding);
          }
        }
        if (parameterValues != null) {
          o = (T) constructor.newInstance(parameterValues);
        }
      }
      if (o == null) {
        o = type.newInstance();
      }
      return o;
    } catch (Exception e) {
      throw new PusherException(e);
    }
  }

  private Object get(E key) {
    Object value = instanceBindings.get(key);
    if (value == NULL) return null;
    return value;
  }

  @Override
  @SuppressWarnings({"unchecked"})
  public <T> T push(T o) {
    Field[] declaredFields = o.getClass().getDeclaredFields();
    for (Field field : declaredFields) {
      Annotation annotation = field.getAnnotation(pushAnnotation);
      if (annotation != null) {
        E fieldBinding;
        try {
          fieldBinding = (E) valueMethod.invoke(annotation);
        } catch (Exception e) {
          throw new PusherException(e);
        }
        Class removed = classBindings.remove(fieldBinding);
        if (removed != null) {
          rebind(fieldBinding, instantiate(removed));
        }
        Object bound = get(fieldBinding);
        if (removed != null) {
          push(bound);
        }
        if (bound == null) {
          throw new PusherException(fieldBinding + " is not bound");
        }
        field.setAccessible(true);
        try {
          field.set(o, bound);
        } catch (Exception e) {
          throw new PusherException(e);
        }
      }
    }
    return o;
  }

  @Override
  public <T> void bindInstance(E binding, T instance) {
    rebind(binding, instance);
  }

  private void rebind(E binding, Object instance) {
    if (instance == null) {
      instance = NULL;
    }
    Object alreadyBound = instanceBindings.put(binding, instance);
    if (alreadyBound == NULL) {
      alreadyBound = null;
    }
    if (alreadyBound != null) {
      logger.warning("Binding rebound: " + binding + " was " + alreadyBound);
    }
  }

  @Override
  public <F> F get(E binding, Class<F> type) {
    Class removed = classBindings.remove(binding);
    if (removed != null) {
      Object o = instantiate(removed);
      rebind(binding, o);
      push(o);
    }
    return (F) get(binding);
  }

  /**
   * Create a new base Pusher.
   *
   * @param simpleBindingClass
   * @param <E>
   * @return
   */
  public static <E> Pusher<E> create(Class<E> simpleBindingClass, Class<? extends Annotation> pushAnnotation) {
    return new PusherBase<E>(simpleBindingClass, pushAnnotation);
  }
}
