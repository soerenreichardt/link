package org.opencypher.link.impl.convert

import org.apache.flink.api.common.typeinfo.{BasicArrayTypeInfo, PrimitiveArrayTypeInfo, TypeInformation}
import org.apache.flink.api.java.typeutils.ObjectArrayTypeInfo
import org.apache.flink.table.api.{TableSchema, Types}
import org.apache.flink.table.expressions.ResolvedFieldReference
import org.opencypher.okapi.api.types.{CTBoolean, CTDate, CTFloat, CTIdentity, CTInteger, CTList, CTNode, CTNull, CTRelationship, CTString, CTVoid, CypherType}
import org.opencypher.okapi.impl.exception.{IllegalArgumentException, IllegalStateException, NotImplementedException}
import org.opencypher.okapi.ir.api.expr.Var
import org.opencypher.okapi.relational.impl.table.RecordHeader

object FlinkConversions {

  val supportedTypes = Seq(
    Types.BYTE,
    Types.SHORT,
    Types.INT,
    Types.LONG,
    Types.FLOAT,
    Types.DOUBLE,
    Types.STRING,
    Types.BOOLEAN
  )

  implicit class CypherTypeOps(val ct: CypherType) extends AnyVal {

    def toFlinkType: Option[TypeInformation[_]] = ct match {
      case CTNull | CTVoid => Some(Types.BOOLEAN) // TODO: boolean is just a dummy
      case _ => ct.material match {
        case CTString => Some(Types.STRING)
        case CTInteger => Some(Types.LONG)
        case CTBoolean => Some(Types.BOOLEAN)
        case CTFloat => Some(Types.DOUBLE)
        case CTIdentity => Some(Types.LONG)
        case CTDate => Some(Types.LONG)
        case _: CTNode => Some(Types.LONG)
        case _: CTRelationship => Some(Types.LONG)
        case l: CTList =>
          l.inner match {
            case CTString =>
              Some(Types.OBJECT_ARRAY(Types.STRING))
            case cType =>
              Some(Types.PRIMITIVE_ARRAY(cType.getFlinkType))
          }

        case _ =>
          None
      }
    }

    def getFlinkType: TypeInformation[_] = toFlinkType match {
      case Some(t) => t
      case None => throw NotImplementedException(s"Mapping of CypherType $ct to Flink type")
    }

    def isFlinkCompatible: Boolean = toFlinkType.isDefined

    def ensureFlinkCompatible(): Unit = getFlinkType
  }

  implicit class RecordHeaderOps(header: RecordHeader) extends Serializable {

    def toResolvedFieldReference: Seq[ResolvedFieldReference] = {
      header.columns.toSeq.sorted.map { column =>
        val expressions = header.expressionsFor(column)
        val commonType = expressions.map(_.cypherType).reduce(_ join _)
        assert(commonType.isFlinkCompatible,
          s"""
             |Expressions $expressions with common super type $commonType mapped to column $column have no compatible data type.
           """.stripMargin)
        ResolvedFieldReference(column, commonType.getFlinkType)
      }
    }
  }

  implicit class TypeOps(val tpe: TypeInformation[_]) extends AnyVal {
    def toCypherType(): Option[CypherType] = {
      val result = tpe match {
        case Types.STRING => Some(CTString)
        case Types.INT => Some(CTInteger)
        case Types.LONG => Some(CTInteger)
        case Types.BOOLEAN => Some(CTBoolean)
        case Types.FLOAT => Some(CTFloat)
        case Types.DOUBLE => Some(CTFloat)
        case PrimitiveArrayTypeInfo.BOOLEAN_PRIMITIVE_ARRAY_TYPE_INFO => Some(CTList(CTBoolean))
        case PrimitiveArrayTypeInfo.DOUBLE_PRIMITIVE_ARRAY_TYPE_INFO => Some(CTList(CTFloat))
        case PrimitiveArrayTypeInfo.FLOAT_PRIMITIVE_ARRAY_TYPE_INFO => Some(CTList(CTFloat))
        case PrimitiveArrayTypeInfo.INT_PRIMITIVE_ARRAY_TYPE_INFO => Some(CTList(CTInteger))
        case PrimitiveArrayTypeInfo.LONG_PRIMITIVE_ARRAY_TYPE_INFO => Some(CTList(CTInteger))
        case basicArray: BasicArrayTypeInfo[_, _] => Some(CTList(basicArray.getComponentInfo.toCypherType().get))
        case objArray: ObjectArrayTypeInfo[_, _] => Some(CTList(objArray.getComponentInfo.toCypherType().get))

        //      TODO: other datatypes
        case _ => None
      }

      result
    }

    def cypherCompatibleDataType: Option[TypeInformation[_]] = tpe match {
      case Types.BYTE | Types.SHORT | Types.INT | Types.DECIMAL => Some(Types.LONG)
      case Types.FLOAT => Some(Types.DOUBLE)
      case compatible if tpe.toCypherType().isDefined => Some(compatible)
      case _ => None
    }
  }

  implicit class TableSchemaOps(val tableSchema: TableSchema) {
    def toRecordHeader: RecordHeader = {
      val exprToColumn = tableSchema.getFieldNames.map { columnName =>
        val cypherType = tableSchema.getFieldType(columnName).orElseGet(
          throw IllegalStateException(s"a missing TypeInformation for column $columnName")
        ).toCypherType() match {
          case Some(ct) => ct
          case None => throw IllegalArgumentException("a supported Flink type", tableSchema.getFieldType(columnName))
        }
        Var(columnName)(cypherType) -> columnName
      }

      RecordHeader(exprToColumn.toMap)
    }
  }
}
