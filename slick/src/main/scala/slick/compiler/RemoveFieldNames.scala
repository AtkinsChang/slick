package slick.compiler

import slick.ast._
import Util._
import TypeUtil._

/** Convert unreferenced StructNodes to single columns or ProductNodes (which is needed for
  * aggregation functions and at the top level). */
class RemoveFieldNames extends Phase {
  val name = "removeFieldNames"

  def apply(state: CompilerState) = state.map { n => ClientSideOp.mapResultSetMapping(n, true) { rsm =>
    val CollectionType(_, NominalType(top, StructType(fdefs))) = rsm.from.nodeType
    val indexes = fdefs.zipWithIndex.map { case ((s, _), i) => (s, ElementSymbol(i+1)) }.toMap
    val rsm2 = rsm.nodeMapServerSide(false, { n =>
      val refTSyms = n.collect[TypeSymbol] { case Select(_ :@ NominalType(s, _), _) => s }.toSet
      val allTSyms = n.collect[TypeSymbol] { case Pure(_, _) :@ CollectionType(_, NominalType(ts, _)) => ts }.toSet
      val unrefTSyms = allTSyms -- refTSyms
      def tr(n: Node): Node =  n.replace {
        case (p @ Pure(s: StructNode, pts)) :@ CollectionType(_, NominalType(ts, _)) if unrefTSyms contains ts =>
          val ch2 = s.children.map(tr)
          Pure(if(ch2.length == 1 && ts != top) ch2(0) else ProductNode(ch2), pts).infer()
      }.infer()
      tr(n)
    })
    logger.debug("Transformed RSM: ", rsm2)
    val CollectionType(_, fType) = rsm2.from.nodeType
    val baseRef = Ref(rsm.generator) :@ fType
    rsm2.copy(map = rsm2.map.replace({
      case Select(Ref(s), f) if s == rsm.generator =>
        Select(baseRef, indexes(f)).infer()
    }, keepType = true)) :@ rsm.nodeType
  }}
}
