package org.opencypher.link.impl.table

import org.apache.flink.table.api.scala._
import org.apache.flink.table.api.{Table, Types}
import org.opencypher.link.api.LinkSession
import org.opencypher.okapi.api.types.CypherType
import org.opencypher.okapi.api.value.CypherValue
import org.opencypher.okapi.ir.api.expr.{Aggregator, Expr, Var}
import org.opencypher.okapi.relational.api.table.{Table => RelationalTable}
import org.opencypher.okapi.relational.impl.planning.{Ascending, CrossJoin, Descending, FullOuterJoin, InnerJoin, JoinType, LeftOuterJoin, Order, RightOuterJoin}
import org.opencypher.okapi.relational.impl.table.RecordHeader
import TableOperations._
import org.apache.flink.table.expressions
import org.apache.flink.table.expressions.{Expression, UnresolvedFieldReference}
import org.opencypher.link.impl.FlinkSQLExprMapper._
import org.opencypher.okapi.impl.exception.IllegalArgumentException
import org.opencypher.link.impl.convert.FlinkConversions._
import org.opencypher.okapi.api.value.CypherValue.CypherMap

object LinkCypherTable {

  implicit class FlinkTable(val table: Table)(implicit val session: LinkSession) extends RelationalTable[FlinkTable] {

    override def physicalColumns: Seq[String] = table.getSchema.getFieldNames

    override def columnType: Map[String, CypherType] = physicalColumns.map(c => c -> table.cypherTypeForColumn(c)).toMap

    override def rows: Iterator[String => CypherValue.CypherValue] = table.collect().iterator.map { row =>
      physicalColumns.map(c => c -> CypherValue(row.getField(table.getSchema.columnNameToIndex(c)))).toMap
    }

    override def size: Long = table.count()

    override def select(col: (String, String), cols: (String, String)*): FlinkTable = {
      val columns = col +: cols
      if (table.columns == columns.map { case (_, alias) => alias }) {
        table
      } else {
        table.select(columns.map { case (colName, alias) => UnresolvedFieldReference(colName) as Symbol(alias) }: _*)
      }
    }

    override def filter(expr: Expr)(implicit header: RecordHeader, parameters: CypherValue.CypherMap): FlinkTable = {
      table.filter(expr.asFlinkSQLExpr(header, table, parameters))
    }

    override def drop(cols: String*): FlinkTable = {
      val columnsLeft = table.physicalColumns.diff(cols)
      select(columnsLeft: _*)
    }

    override def join(other: FlinkTable, joinType: JoinType, joinCols: (String, String)*): FlinkTable = {
      val overlap = this.physicalColumns.toSet.intersect(other.physicalColumns.toSet)
      assert(overlap.isEmpty, s"overlapping columns: $overlap")

      val joinExpr = joinCols.map {
        case (l, r) => UnresolvedFieldReference(l) === UnresolvedFieldReference(r)
      }.foldLeft(expressions.Literal(true, Types.BOOLEAN): Expression) { (acc, expr) => acc && expr }

      joinType match {
        case InnerJoin => table.join(other.table, joinExpr)
        case LeftOuterJoin => table.leftOuterJoin(other.table, joinExpr)
        case RightOuterJoin => table.rightOuterJoin(other.table, joinExpr)
        case FullOuterJoin => table.fullOuterJoin(other.table, joinExpr)
        case CrossJoin => table.cross(other.table)
      }
    }

    override def unionAll(other: FlinkTable): FlinkTable = {
      val leftTypes = table.getSchema.getFieldTypes.flatMap(_.toCypherType())
      val rightTypes = other.table.getSchema.getFieldTypes.flatMap(_.toCypherType())

      leftTypes.zip(rightTypes).foreach {
        case (leftType, rightType) if !leftType.nullable.couldBeSameTypeAs(rightType.nullable) =>
          val fieldsWithType = table.getSchema.getFieldNames.zip(table.getSchema.getFieldTypes)
          val otherFieldsWithType = other.table.getSchema.getFieldNames.zip(other.table.getSchema.getFieldTypes)

          throw IllegalArgumentException(
            "Equal column types for union all (differing nullability is OK)",
            s"Left fields: ${fieldsWithType.mkString(", ")}\n\tRight fields: ${otherFieldsWithType.mkString(", ")}"
          )
        case _ =>
      }

      table.union(other.table)
    }

    override def orderBy(sortItems: (Expr, Order)*)(implicit header: RecordHeader, parameters: CypherMap): FlinkTable = {
      val mappedSortItems = sortItems.map { case (expr, order) =>
        val mappedExpr = expr.asFlinkSQLExpr(header, table, parameters)
        order match {
          case Ascending => mappedExpr.asc
          case Descending => mappedExpr.desc
        }
      }
      table.orderBy(mappedSortItems: _*)
    }

    override def skip(n: Long): FlinkTable = table.offset(n.toInt)

    override def limit(n: Long): FlinkTable = table.fetch(n.toInt)

    override def distinct: FlinkTable = table.distinct()

    override def distinct(cols: String*): FlinkTable = ???

    override def group(by: Set[Var], aggregations: Map[String, Aggregator])(implicit header: RecordHeader, parameters: CypherValue.CypherMap): FlinkTable = ???

    override def withColumns(columns: (Expr, (String, Option[CypherType]))*)
      (implicit header: RecordHeader, parameters: CypherValue.CypherMap): FlinkTable = {
      val initialColumnNameToFieldReference: Map[String, Expression] =
        table.columns.map(c => c -> UnresolvedFieldReference(c)).toMap
      val updatedColumns = columns.foldLeft(initialColumnNameToFieldReference) { case (columnMap, (expr, (columnName, castType))) =>
        val convertedExpr = expr.asFlinkSQLExpr(header, table, parameters)
        val castedExpr = if (castType.isDefined) {
          convertedExpr.cast(castType.get.getFlinkType)
        } else {
          convertedExpr
        }
        val processedExpr = castedExpr.as(Symbol(columnName))
        columnMap + (columnName -> processedExpr)
      }
      val existingColumnNames = table.columns
      val columnsForSelect = existingColumnNames.map(updatedColumns) ++
      updatedColumns.filterKeys(!existingColumnNames.contains(_)).values

      table.select(columnsForSelect: _*)
    }

    override def show(rows: Int): Unit = ???
  }
}
