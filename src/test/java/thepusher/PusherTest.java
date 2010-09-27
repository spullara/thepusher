package thepusher;

import org.junit.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.Assert.assertEquals;
import static thepusher.PusherTest.SimpleBinding.PASSWORD;
import static thepusher.PusherTest.SimpleBinding.PUSHED;
import static thepusher.PusherTest.SimpleBinding.USERNAME;

/**
 * TODO: Edit this
 * <p/>
 * User: sam
 * Date: Sep 27, 2010
 * Time: 1:19:29 PM
 */
public class PusherTest {

  @Target({ElementType.FIELD, ElementType.PARAMETER})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Push {
    SimpleBinding value();
  }

  public enum SimpleBinding {
    USERNAME,
    PASSWORD,
    PUSHED
  }

  public static class Pushed {
    @Push(USERNAME) String username;
    @Push(PASSWORD) String password;
  }
  
  public static class Pushed2 {
    public Pushed2(@Push(USERNAME) String username, @Push(PASSWORD) String password) {
      this.username = username;
      this.password = password;
    }
    String username;
    String password;
  }

  public static class Pushed3 {
    public Pushed3(@Push(USERNAME) String username) {
      this.username = username;
    }
    String username;
    @Push(PASSWORD) String password;
  }

  @Test
  public void simpleBind() {
    Pusher<SimpleBinding> p = PusherBase.create(SimpleBinding.class, Push.class);
    p.bindInstance(USERNAME, "sam");
    p.bindInstance(PASSWORD, "blah");

    String username = p.get(USERNAME, String.class);
    assertEquals("sam", username);

    p.bindClass(PUSHED, Pushed.class);

    Pushed pushed = p.get(PUSHED, Pushed.class);
    assertEquals("sam", pushed.username);
    assertEquals("blah", pushed.password);

    p.bindClass(PUSHED, Pushed2.class);

    Pushed2 pushed2 = p.get(PUSHED, Pushed2.class);
    assertEquals("sam", pushed2.username);
    assertEquals("blah", pushed2.password);

    p.bindClass(PUSHED, Pushed3.class);

    Pushed3 pushed3 = p.get(PUSHED, Pushed3.class);
    assertEquals("sam", pushed2.username);
    assertEquals("blah", pushed2.password);
  }

  public static class B {
  }

  public static class A {
    private final B b;

    public A(@Push(USERNAME) B b) {
      this.b = b;
    }
  }

  public static class Pushed4 {
    @Push(PUSHED) A a;
  }

  @Test
  public void abTest() {
    Pusher<SimpleBinding> p = PusherBase.create(SimpleBinding.class, Push.class);
    p.bindClass(PUSHED, A.class);
    B b = new B();
    p.bindInstance(USERNAME, b);

    Pushed4 pushed4 = new Pushed4();
    p.push(pushed4);
    assertEquals(b, pushed4.a.b);
  }
  
  public static class D {
    @Push(PUSHED) E e;
  }

  public static class E {
    @Push(PASSWORD) C c;
  }

  public static class C {
    private final D d;

    public C(@Push(USERNAME) D d) {
      this.d = d;
    }
  }

  @Test
  public void cyclicTest() {
    Pusher<SimpleBinding> p = PusherBase.create(SimpleBinding.class, Push.class);
    p.bindClass(USERNAME, D.class);
    p.bindClass(PASSWORD, C.class);
    p.bindClass(PUSHED, E.class);

    D d = p.get(USERNAME, D.class);
    C c = p.get(PASSWORD, C.class);
    E e = p.get(PUSHED, E.class);
    assertEquals(d, c.d);
    assertEquals(e, d.e);
    assertEquals(c, e.c);
  }

}
