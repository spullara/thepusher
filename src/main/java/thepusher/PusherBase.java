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
 * Pushes values into objects.
 * <p/>
 * User: sam
 * Date: Sep 27, 2010
 * Time: 1:14:48 PM
 */
public class PusherBase<E> implements Pusher<E> {
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
      for (Constructor constructor : declaredConstructors) {
        Annotation[][] parameterAnnotations = constructor.getParameterAnnotations();
        int length = parameterAnnotations.length;
        if (length == 0) continue;
        Object[] parameterValues = null;
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
          }
          // Possibly a valid constructor
          if (parameterValues == null) {
            parameterValues = new Object[length];
          }
          //noinspection SuspiciousMethodCalls
          parameterValues[i] = instanceBindings.get(valueMethod.invoke(foundAnnotation));
        }
        if (parameterValues != null) {
          //noinspection unchecked
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

  @Override
  @SuppressWarnings({"unchecked"})
  public <T> void push(T o) {
    try {
      Field[] declaredFields = o.getClass().getDeclaredFields();
      for (Field field : declaredFields) {
        Annotation annotation = field.getAnnotation(pushAnnotation);
        if (annotation != null) {
          E fieldBinding = (E) valueMethod.invoke(annotation);
          Class removed = classBindings.remove(fieldBinding);
          if (removed != null) {
            instanceBindings.put(fieldBinding, instantiate(removed));
          }
          Object bound = instanceBindings.get(fieldBinding);
          if (bound == null) {
            throw new PusherException(fieldBinding + " is not bound");
          }
          field.setAccessible(true);
          field.set(o, bound);
          if (removed != null) {
            push(instanceBindings.get(fieldBinding));
          }
        }
      }
    } catch (Exception e) {
      throw new PusherException(e);
    }
  }

  @Override
  public <T> void bindInstance(E binding, T instance) {
    rebind(binding, instance);
  }

  private void rebind(E binding, Object instance) {
    Object alreadyBound = instanceBindings.put(binding, instance);
    if (alreadyBound != null) {
      logger.warning("Binding rebound: " + binding + " was " + alreadyBound);
    }
  }

  @SuppressWarnings({"unchecked"})
  @Override
  public <F> F get(E binding, Class<F> type) {
    Class removed = classBindings.remove(binding);
    if (removed != null) {
      Object o = instantiate(removed);
      instanceBindings.put(binding, o);
      push(o);
    }
    return (F) instanceBindings.get(binding);
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
