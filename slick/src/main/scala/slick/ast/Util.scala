package slick.ast

import slick.ast.TypeUtil.:@

import scala.language.implicitConversions
import scala.collection.mutable.ArrayBuffer

/**
 * Utility methods for AST manipulation.
 */
object Util {

  def mapOrNone[A <: AnyRef](c: Traversable[A])(f: A => A): Option[IndexedSeq[A]] = {
    val b = new ArrayBuffer[A]
    var changed = false
    c.foreach { x =>
      val n = f(x)
      b += n
      if(n ne x) changed = true
    }
    if(changed) Some(b.result()) else None
  }

  @inline implicit def nodeToNodeOps(n: Node): NodeOps = new NodeOps(n)
}

/** Extra methods for Nodes. */
final class NodeOps(val tree: Node) extends AnyVal {
  import Util._

  @inline def collect[T](pf: PartialFunction[Node, T], stopOnMatch: Boolean = false): Seq[T] = {
    val b = new ArrayBuffer[T]
    def f(n: Node): Unit = pf.andThen[Unit] { case t =>
      b += t
      if(!stopOnMatch) n.children.foreach(f)
    }.orElse[Node, Unit]{ case _ =>
      n.children.foreach(f)
    }.apply(n)
    f(tree)
    b
  }

  def collectAll[T](pf: PartialFunction[Node, Seq[T]]): Seq[T] = collect[Seq[T]](pf).flatten

  def replace(f: PartialFunction[Node, Node], keepType: Boolean = false, bottomUp: Boolean = false): Node = {
    def g(n: Node): Node = n.mapChildren(_.replace(f, keepType, bottomUp), keepType)
    if(bottomUp) f.applyOrElse(g(tree), identity[Node]) else f.applyOrElse(tree, g)
  }

  /** Replace nodes in a bottom-up traversal with an extra state value that gets passed through the
    * traversal. Types are never kept or rebuilt when a node changes. */
  def replaceFold[T](z: T)(f: PartialFunction[(Node, T), (Node, T)]): (Node, T) = {
    var v: T = z
    val ch: IndexedSeq[Node] = tree.children.map { n =>
      val (n2, v2) = n.replaceFold(v)(f)
      v = v2
      n2
    }(collection.breakOut)
    val t2 = tree.withChildren(ch)
    f.applyOrElse((t2, v), identity[(Node, T)])
  }

  /** Replace nodes in a bottom-up traversal while invalidating TypeSymbols. Any later references
    * to the invalidated TypeSymbols have their types unassigned, so that the whole tree can be
    * retyped afterwards to get the correct new TypeSymbols in. */
  def replaceInvalidate(f: PartialFunction[(Node, Set[TypeSymbol]), (Node, Set[TypeSymbol])]): Node =
    replaceFold(Set.empty[TypeSymbol])(f.orElse {
      case ((n: Ref) :@ NominalType(ts, _), invalid) if invalid(ts) => (n.untyped, invalid)
      case ((n: Select) :@ NominalType(ts, _), invalid) if invalid(ts) => (n.untyped, invalid)
    })._1

  def foreach[U](f: (Node => U)): Unit = {
    def g(n: Node) {
      f(n)
      n.children.foreach(g)
    }
    g(tree)
  }

  def findNode(p: Node => Boolean): Option[Node] = {
    if(p(tree)) Some(tree)
    else {
      val it = tree.children.iterator.map(_.findNode(p)).dropWhile(_.isEmpty)
      if(it.hasNext) it.next() else None
    }
  }

  def select(field: TermSymbol): Node = (field, tree) match {
    case (s: AnonSymbol, StructNode(ch)) => ch.find{ case (s2,_) => s == s2 }.get._2
    case (s: FieldSymbol, StructNode(ch)) => ch.find{ case (s2,_) => s == s2 }.get._2
    case (s: ElementSymbol, ProductNode(ch)) => ch(s.idx-1)
    case (s, n) => Select(n, s)
  }
}
