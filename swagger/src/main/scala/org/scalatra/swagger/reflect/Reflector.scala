package org.scalatra.swagger.reflect

import java.{util => jutil}
import java.lang.reflect.{Type, TypeVariable, ParameterizedType, Modifier}
import scala.util.control.Exception._
import collection.JavaConverters._
import java.util.Date
import java.sql.Timestamp

object Reflector {

  private[this] val rawClasses = new Memo[Type, Class[_]]
  private[this] val unmangledNames = new Memo[String, String]
  private[this] val descriptors = new Memo[ScalaType, Descriptor]

  private[this] val primitives = {
      Set[Type](classOf[String], classOf[Char], classOf[Int], classOf[Long], classOf[Double],
        classOf[Float], classOf[Byte], classOf[BigInt], classOf[Boolean],
        classOf[Short], classOf[java.lang.Integer], classOf[java.lang.Long],
        classOf[java.lang.Double], classOf[java.lang.Float], classOf[java.lang.Character],
        classOf[java.lang.Byte], classOf[java.lang.Boolean], classOf[Number],
        classOf[java.lang.Short], classOf[Date], classOf[Timestamp], classOf[Symbol], classOf[Unit])
  }

  private[this] val defaultExcluded = Set(classOf[Nothing], classOf[Null])

  def isPrimitive(t: Type) = primitives contains t

  def isExcluded(t: Type, excludes: Seq[Type] = Nil) = (defaultExcluded ++ excludes) contains t

  def scalaTypeOf[T](implicit mf: Manifest[T]): ScalaType = {
    ScalaType(mf)
  }

  def scalaTypeOf(clazz: Class[_]): ScalaType = {
    val mf = ManifestFactory.manifestOf(clazz)
    ScalaType(mf)
  }

  def scalaTypeOf(t: Type): ScalaType = {
    val mf = ManifestFactory.manifestOf(t)
    ScalaType(mf)
  }

  def scalaTypeOf(name: String): Option[ScalaType] = Reflector.resolveClass[AnyRef](name, ClassLoaders) map (c => scalaTypeOf(c))

  def describe[T](implicit mf: Manifest[T]): Descriptor = describe(scalaTypeOf[T])

  def describe(clazz: Class[_]): Descriptor = describe(scalaTypeOf(clazz))

  def describe(fqn: String): Option[Descriptor] =
    Reflector.scalaTypeOf(fqn) map (describe(_: ScalaType))

  def describe(st: ScalaType): Descriptor = {
    if (st.isCollection || st.isOption) describe(st.typeArgs.head)
    descriptors(st, Reflector.createClassDescriptor)
  }


  def resolveClass[X <: AnyRef](c: String, classLoaders: Iterable[ClassLoader]): Option[Class[X]] = classLoaders match {
    case Nil => sys.error("resolveClass: expected 1+ classloaders but received empty list")
    case List(cl) => Some(Class.forName(c, true, cl).asInstanceOf[Class[X]])
    case many => {
      try {
        var clazz: Class[_] = null
        val iter = many.iterator
        while (clazz == null && iter.hasNext) {
          try {
            clazz = Class.forName(c, true, iter.next())
          }
          catch {
            case e: ClassNotFoundException => // keep going, maybe it's in the next one
          }
        }

        if (clazz != null) Some(clazz.asInstanceOf[Class[X]]) else None
      }
      catch {
        case _: Throwable => None
      }
    }
  }

  private[reflect] def createClassDescriptor(tpe: ScalaType): Descriptor = {

    def properties: Seq[PropertyDescriptor] = {
      def fields(clazz: Class[_]): List[PropertyDescriptor] = {
        val lb = new jutil.LinkedList[PropertyDescriptor]().asScala
        val ls = clazz.getDeclaredFields.toIterator
        while (ls.hasNext) {
          val f = ls.next()
          if (!Modifier.isStatic(f.getModifiers) || !Modifier.isTransient(f.getModifiers) || !Modifier.isPrivate(f.getModifiers)) {
            val st = ScalaType(f.getType, f.getGenericType match {
              case p: ParameterizedType => p.getActualTypeArguments map (c => scalaTypeOf(c))
              case _ => Nil
            })
            val decoded = unmangleName(f.getName)
            f.setAccessible(true)
            lb += PropertyDescriptor(decoded, f.getName, st, f)
          }
        }
        if (clazz.getSuperclass != null)
          lb ++= fields(clazz.getSuperclass)
        lb.toList
      }
      fields(tpe.erasure)
    }

    def ctorParamType(name: String, index: Int, owner: ScalaType, ctorParameterNames: List[String], t: Type, container: Option[(ScalaType, List[Int])] = None): ScalaType = {
      val idxes = container.map(_._2.reverse)
      t  match {
        case v: TypeVariable[_] =>
          val a = owner.typeVars.getOrElse(v, scalaTypeOf(v))
          if (a.erasure == classOf[java.lang.Object]) {
            val r = ScalaSigReader.readConstructor(name, owner, index, ctorParameterNames)
            scalaTypeOf(r)
          } else a
        case v: ParameterizedType =>
          val st = scalaTypeOf(v)
          val actualArgs = v.getActualTypeArguments.toList.zipWithIndex map {
            case (ct, idx) =>
              val prev = container.map(_._2).getOrElse(Nil)
              ctorParamType(name, index, owner, ctorParameterNames, ct, Some((st, idx :: prev)))
          }
          st.copy(typeArgs = actualArgs)
        case x =>
          val st = scalaTypeOf(x)
          if (st.erasure == classOf[java.lang.Object])
            scalaTypeOf(ScalaSigReader.readConstructor(name, owner, idxes getOrElse List(index), ctorParameterNames))
          else st
      }
    }

    def constructors(companion: Option[SingletonDescriptor]): Seq[ConstructorDescriptor] = {
      tpe.erasure.getConstructors.toSeq map {
        ctor =>
          val ctorParameterNames = ParanamerReader.lookupParameterNames(ctor)
          val genParams = Vector(ctor.getGenericParameterTypes: _*)
          val ctorParams = ctorParameterNames.zipWithIndex map {
            case (paramName, index) =>
              val decoded = unmangleName(paramName)
              val default = companion flatMap {
                comp =>
                  defaultValue(comp.erasure.erasure, comp.instance, index)
              }
              val theType = ctorParamType(paramName, index, tpe, ctorParameterNames.toList, genParams(index))
              ConstructorParamDescriptor(decoded, paramName, index, theType, default)
          }
          ConstructorDescriptor(ctorParams.toSeq, ctor, isPrimary = false)
      }
    }

    if (tpe.isPrimitive) PrimitiveDescriptor(tpe.simpleName, tpe.fullName, tpe)
    else {
      val path = if (tpe.rawFullName.endsWith("$")) tpe.rawFullName else "%s$".format(tpe.rawFullName)
      val c = resolveClass(path, Vector(getClass.getClassLoader))
      val companion = c flatMap { cl =>
        allCatch opt {
          SingletonDescriptor(cl.getSimpleName, cl.getName, scalaTypeOf(cl), cl.getField(ModuleFieldName).get(null), Seq.empty)
        }
      }
      ClassDescriptor(tpe.simpleName, tpe.fullName, tpe, companion, constructors(companion), properties)
    }
  }

  def defaultValue(compClass: Class[_], compObj: AnyRef, argIndex: Int) = {
    allCatch.withApply(_ => None) {
      Option(compClass.getMethod("%s$%d".format(ConstructorDefault, argIndex + 1))) map {
        meth => () => meth.invoke(compObj)
      }
    }
  }

  def rawClassOf(t: Type): Class[_] = rawClasses(t, _ match {
    case c: Class[_] => c
    case p: ParameterizedType => rawClassOf(p.getRawType)
    case x => sys.error("Raw type of " + x + " not known")
  })

  def unmangleName(name: String) =
    unmangledNames(name, scala.reflect.NameTransformer.decode)


}