package slick.compiler

import slick.ast._
import Util._
import TypeUtil._
import QueryParameter.constOp

import scala.collection.mutable

/** Remove all occurrences of `Take` and `Drop` with row number computations based on
  * `zipWithIndex` operations. */
class RemoveTakeDrop extends Phase {
  val name = "removeTakeDrop"

  def apply(state: CompilerState) = state.map(_.replaceInvalidate {
    case (n @ TakeDrop(from, t, d), invalid) =>
      logger.debug(s"""Translating "drop $d, then take $t" to zipWithIndex operation:""", n)
      val from2 = from match {
        case b: Bind => b
        case n =>
          val s = new AnonSymbol
          Bind(s, n, Pure(Ref(s)))
      }
      val j = Join(new AnonSymbol, new AnonSymbol, from2, RangeFrom(1L), JoinType.Zip, LiteralNode(true))
      val bs1 = new AnonSymbol
      val b1 = Bind(bs1, j, Pure(Ref(bs1)))
      val fs = new AnonSymbol
      val f = Filter(fs, b1, (t, d) match {
        case (None, Some(d)) => Library.>.typed[Boolean](Select(Ref(fs), ElementSymbol(2)), d)
        case (Some(t), None) => Library.<=.typed[Boolean](Select(Ref(fs), ElementSymbol(2)), t)
        case (Some(t), Some(d)) =>
          Library.And.typed[Boolean](
            Library.>.typed[Boolean](Select(Ref(fs), ElementSymbol(2)), d),
            Library.<=.typed[Boolean](Select(Ref(fs), ElementSymbol(2)), constOp[Long]("+")(_ + _)(t, d))
          )
      })
      val bs2 = new AnonSymbol
      val b2 = Bind(bs2, f, Pure(Select(Ref(bs2), ElementSymbol(1))))
      logger.debug(s"""Translated "drop $d, then take $t" to zipWithIndex operation:""", b2)
      val invalidate = from.nodeType.collect { case NominalType(ts, _) => ts }
      logger.debug("Invalidating TypeSymbols: "+invalidate.mkString(", "))
      (b2, invalid ++ invalidate)
  }.infer())

  /** An extractor for nested Take and Drop nodes */
  object TakeDrop {
    def unapply(n: Node): Option[(Node, Option[Node], Option[Node])] = n match {
      case Take(from, num) => unapply(from) match {
        case Some((f, Some(t), d)) => Some((f, Some(constOp[Long]("min")(math.min)(t, num)), d))
        case Some((f, None, d)) => Some((f, Some(num), d))
        case _ => Some((from, Some(num), None))
      }
      case Drop(from, num) => unapply(from) match {
        case Some((f, Some(t), None)) => Some((f, Some(constOp[Long]("max")(math.max)(LiteralNode(0L).infer(), constOp[Long]("-")(_ - _)(t, num))), Some(num)))
        case Some((f, None, Some(d))) => Some((f, None, Some(constOp[Long]("+")(_ + _)(d, num))))
        case Some((f, Some(t), Some(d))) => Some((f, Some(constOp[Long]("max")(math.max)(LiteralNode(0L).infer(), constOp[Long]("-")(_ - _)(t, num))), Some(constOp[Long]("+")(_ + _)(d, num))))
        case _ => Some((from, None, Some(num)))
      }
      case _ => None
    }
  }
}
