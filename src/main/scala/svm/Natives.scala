package svm

import annotation.tailrec
import java.util.concurrent.atomic.AtomicInteger

object Natives {

  var internedStrings: List[svm.Object] = Nil
  def intern(x: svm.Object) = {

    println("INTERNING " + Virtualizer.fromVirtual(x))
    val interned = internedStrings.find(y => Virtualizer.fromVirtual(y) == Virtualizer.fromVirtual(x))
    interned match{
      case Some(i) => i
      case None =>
        internedStrings ::= x
        x
    }
  }
  val noOp = () => ()
  val noOp1 = (x: Any) => ()
  val noOp2 = (x: Any, y: Any) => ()
  val primitiveMap = Map(
    "boolean" -> "java/lang/Boolean",
    "byte" -> "java/lang/Byte",
    "short" -> "java/lang/Short",
    "int" -> "java/lang/Integer",
    "long" -> "java/lang/Long",
    "float" -> "java/lang/Float",
    "double" -> "java/lang/Double"
  )

  def trapped = Route(
    "sun"/(
      "reflect"/(
        "Reflection"/(
          "registerMethodsToFilter(Ljava/lang/Class;[Ljava/lang/String;)V" - (noOp2)
        )
      )
    )
  )
  def nativeX(thread: VmThread, stackTrace: () => List[StackTraceElement])(implicit getClassFor: String => Class): Route = Route(
    "java"/(
      "lang"/(
        "Class"/(
          "registerNatives()V"-noOp,
          "getName0()Ljava/lang/String;" - ((s: svm.ClassObject) => Virtualizer.toVirtual[svm.Object](s.name.replace("/", "."))),
          "forName0(Ljava/lang/String;)Ljava/lang/Class;" - ((s: svm.Object) => new ClassObject(Virtualizer.fromVirtual[String](s))),
          "forName0(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;" - ((s: svm.Object, x: Any, w: Any, y: Any) => new ClassObject(Virtualizer.fromVirtual[String](s))),
          "getPrimitiveClass(Ljava/lang/String;)Ljava/lang/Class;"-((s: svm.Object) => (new Object(getClassFor(primitiveMap(Virtualizer.fromVirtual(s).asInstanceOf[String]))))),
          "getClassLoader0()Ljava/lang/ClassLoader;"-((x: Any) => null),
          "getDeclaringClass()Ljava/lang/Class;" - {() =>
            null
          },
          "getDeclaredFields0(Z)[Ljava/lang/reflect/Field;" - {(cls: svm.ClassObject, b: Int) =>
            cls.getDeclaredFields().toArray
          },
          "getEnclosingMethod0()[Ljava/lang/Object;" - { () =>
            null
          },
          "isPrimitive()Z" - ((x: svm.ClassObject) => false),
          "isArray()Z" - ((x: svm.ClassObject) => x.name.startsWith("[")),
          "desiredAssertionStatus0(Ljava/lang/Class;)Z"-((x: Any) => 0)
        ),
        "Double"/(
          "doubleToRawLongBits(D)J"-{ java.lang.Double.doubleToRawLongBits(_: Double)},
          "longBitsToDouble(J)D"-{ java.lang.Double.longBitsToDouble(_: Long)}
        ),
        "Float"/(
          "intBitsToFloat(I)F"-{ java.lang.Float.intBitsToFloat(_: Int)},
          "floatToRawIntBits(F)I"-{ java.lang.Float.floatToIntBits(_: Float)}
        ),
        "Object"/(
          "registerNatives()V"-noOp,
          "getClass()Ljava/lang/Class;" - { (_: Any) match{
            case (x: svm.Object) =>
              println("GETTING CLASS " + x.cls.name)
              new ClassObject(x.cls.name)
            case a: Array[_] => new ClassObject(a.getClass.getName)
          }},
          "hashCode()I" - {(x: svm.Object) => x.hashCode()},
          "notify()V" - noOp1,
          "notifyAll()V" - noOp1
        ),
        "Runtime"/(
            "freeMemory()J" - {() => 1000000000L  }
        ),
        "String"/(
          "intern()Ljava/lang/String;" - intern _
        ),
        "System"/(
          "arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V"-((src: Any, srcPos: Int, dest: Any, destPos: Int, length: Int) =>
            System.arraycopy(src, srcPos, dest, destPos, length)
          ),
          "currentTimeMillis()J"-(() => System.currentTimeMillis()),
          "nanoTime()J"-(() => System.nanoTime()),
          "identityHashCode(Ljava/lang/Object;)I"-((x: svm.Object) => System.identityHashCode(x)),
          "registerNatives()V"-noOp
        ),
        "Thread"/(
          "currentThread()Ljava/lang/Thread;" - { () =>
            new svm.Object("java/lang/Thread",
              "name" -> "MyThread".toCharArray,
              "group" -> new svm.Object("java/lang/ThreadGroup"),
              "priority" -> 5
            )

          },
          "setPriority0(I)V" - { noOp2 },
          "start0()V" - { noOp }
        ),
        "Throwable"/(
          "fillInStackTrace(I)Ljava/lang/Throwable;" - { (throwable: svm.Object, dummy: Int) =>
            throwable.members(0)("stackTrace") =
              stackTrace().map { f =>
              new svm.Object("java/lang/StackTraceElement",
                "declaringClass" -> f.getClassName,
                "methodName" -> f.getMethodName,
                "fileName" -> f.getFileName,
                "lineNumber" -> f.getLineNumber
              )
            }
            throwable
          }
        ),
        "ref"/(
          "Reference$ReferenceHandler"/(
            "isAlive()Z" - {() => false}
          )
        )

      ),
      "security"/(
        "AccessController"/(
          "doPrivileged(Ljava/security/PrivilegedAction;)Ljava/lang/Object;" - {
            (pa: svm.Object) =>
              println("doPrivilegedyo")
              println(pa.cls.name)
              println(pa.cls.classData.methods.map(_.name))
              thread.prepInvoke(pa.cls, pa.cls.classData.methods.find(_.name == "run").get, Nil)
          },
          "getStackAccessControlContext()Ljava/security/AccessControlContext;" - {
            () => new svm.Object("java/security/AccessControlContext")
          },
          "getInheritedAccessControlContext()Ljava/security/AccessControlContext;" - {
            () => new svm.Object("java/security/AccessControlContext")
          }
        )
      )
    ),
    "sun"/(
      "misc"/(
        "Unsafe"/(
          "arrayBaseOffset(Ljava/lang/Class;)I" - ((x: Any) => 0),
          "arrayIndexScale(Ljava/lang/Class;)I" - ((x: Any) => 1),
          "addressSize()I" - ((x: Any) => 4),
          "compareAndSwapInt(Ljava/lang/Object;JII)Z" - {(s: Any, x: svm.Object, offset: Int, expected: Int, target: Int) =>
            println("COMPAREANDSWAP LOL")
            println(x.members.head)
            val key = x.members.head.keys.toSeq(offset.toInt)
            println(key)
            if (x(x.cls.name, key) == expected) x(x.cls.name, key) = target
            true;
          },
          "objectFieldOffset(Ljava/lang/reflect/Field;)J" - {(x: svm.Object) => 0 }
        ),
        "VM"/(
          "initialize()V" - noOp
        )
      ),

      "reflect"/(
        "Reflection"/(
          "getCallerClass(I)Ljava/lang/Class;" - { (n: Int) =>
            new ClassObject(stackTrace().drop(n).head.getClassName)
          }
        )
      )
    )

  )
  object Route{
    def apply(a: (String, Route)*) = Node(a.toMap)
  }
  trait Route{
    def lookup(s: String): Option[Leaf]
  }
  case class Node(children: Map[String, Route]) extends Route{
    def lookup(s: String) = {
      val Array(first, rest) =
        if (s.indexOf('/') != -1 && s.indexOf('/') < s.indexOf('(')){
          s.split("/", 2)
        }else{
          Array(s, "")
        }
      children.get(first).flatMap(_.lookup(rest))
    }
  }

  implicit def func0(f: () => Any) = (x: Any) => f()
  implicit def func2(f: (Nothing, Nothing) => Any) = f.curried
  implicit def func3(f: (Nothing, Nothing, Nothing) => Any) = f.curried
  implicit def func4(f: (Nothing, Nothing, Nothing, Nothing) => Any) = f.curried
  implicit def func5(f: (Nothing, Nothing, Nothing, Nothing, Nothing) => Any) = f.curried
  case class Leaf(f: Nothing => Any) extends Route{
    def lookup(s: String) = {
      if (s == "") Some(this)
      else None
    }
    def apply(s: Seq[Any]) = {
      @tailrec def rec(f: Any, s: Seq[Any]): Any = (f, s) match {
        case (f1: Function1[Any, Any], head :: tail) => rec(f1(head), tail)
        case (f1: Function1[Any, Any], _) => f1(null)
        case (x, _) => x
      }
      rec(f, s)
    }
  }

  implicit class pimpedMap(s: String){
    def /(a: (String, Route)*) = s -> Node(a.toMap)
    def -(a: Nothing => Any) = s -> Leaf(a)
  }
}
