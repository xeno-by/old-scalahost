package scala.reflect.internal.hosts
package scalacompiler
package scalahost

import scala.{Seq => _}
import scala.collection.immutable.Seq
import scala.reflect.core._
import scala.reflect.semantic._
import scala.tools.nsc.{Global => ScalaGlobal}
import scala.reflect.macros.contexts.{Context => ScalaMacroContext}
import scala.reflect.semantic.{MacroHost => PalladiumMacroHost}

class MacroHost[G <: ScalaGlobal](val c: ScalaMacroContext) extends Host[G](c.universe.asInstanceOf[G]) with PalladiumMacroHost {
  def warning(msg: String): Unit = ???
  def error(msg: String): Unit = ???
  def abort(msg: String): Nothing = ???
  def resources: Seq[String] = ???
  def resource(url: String): Array[Byte] = ???
}