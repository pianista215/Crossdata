/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.crossdata.execution.datasources

import java.sql.Timestamp
import java.text.SimpleDateFormat

import com.stratio.common.utils.components.logger.impl.SparkLoggerComponent
import com.stratio.crossdata.connector.{TableInventory, TableManipulation}
import com.sun.org.apache.xalan.internal.xsltc.compiler.util.IntType
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, LogicalPlan}
import org.apache.spark.sql.crossdata.XDContext
import org.apache.spark.sql.crossdata.catalog.XDCatalog
import org.apache.spark.sql.crossdata.catalog.XDCatalog._
import org.apache.spark.sql.execution.RunnableCommand
import org.apache.spark.sql.execution.datasources.{LogicalRelation, ResolvedDataSource}
import org.apache.spark.sql.sources.{BaseRelation, InsertableRelation}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{AnalysisException, DataFrame, Row, SQLContext}

import scala.language.implicitConversions
import scala.reflect.io.File
import scala.util.{Failure, Success, Try}

object DDLUtils {

  implicit def tableIdentifierToSeq(tableIdentifier: TableIdentifier): Seq[String] =
    tableIdentifier.database.toSeq :+ tableIdentifier.table

  def convertSparkDatatypeToScala(value: String, sparkDataType: DataType): Try[Any] = {
    sparkDataType match {
      case _: ByteType => Try{value.toByte}
      case _: ShortType => Try{value.toShort}
      case _: IntegerType => Try{value.toInt}
      case _: LongType => Try{value.toLong}
      case _: FloatType => Try{value.toFloat}
      case _: DoubleType => Try{value.toDouble}
      case _: DecimalType => Try{BigDecimal(value)}
      case _: StringType => Try{value.toString}
      case _: BooleanType => Try{value.toBoolean}
      case _: DateType => Try{new SimpleDateFormat().parse(value)}
      case _: TimestampType => Try{Timestamp.valueOf(value)}
      case _ => Failure(new RuntimeException("Invalid Spark DataType"))
    }
  }

}

private[crossdata] case class ImportTablesUsingWithOptions(datasource: String, opts: Map[String, String])
  extends LogicalPlan with RunnableCommand with SparkLoggerComponent {

  // The result of IMPORT TABLE has only tableIdentifier so far.
  override val output: Seq[Attribute] = {
    val schema = StructType(
      Seq(StructField("tableIdentifier", ArrayType(StringType), false), StructField("ignored", BooleanType, false))
    )
    schema.toAttributes
  }

  override def run(sqlContext: SQLContext): Seq[Row] = {

    def tableExists(tableId: TableIdentifier): Boolean = {
      val doExist = sqlContext.catalog.tableExists(tableId)
      if (doExist) log.warn(s"IMPORT TABLE omitted already registered table: ${tableId.unquotedString}")
      doExist
    }

    // Get a reference to the inventory relation.
    val resolved = ResolvedDataSource.lookupDataSource(datasource).newInstance()
    val inventoryRelation = resolved.asInstanceOf[TableInventory]

    // Obtains the list of tables and persist it (if persistence implemented)
    val tables = inventoryRelation.listTables(sqlContext, opts)

    for {
      table: TableInventory.Table <- tables
      tableId = TableIdentifier(table.tableName, table.database)
      if inventoryRelation.exclusionFilter(table)
    } yield {
      val ignoreTable = tableExists(tableId)
      if (!ignoreTable) {
        logInfo(s"Importing table ${tableId.unquotedString}")
        val optionsWithTable = inventoryRelation.generateConnectorOpts(table, opts)
        val crossdataTable = CrossdataTable(table.tableName, table.database, table.schema, datasource, Array.empty[String], optionsWithTable)
        sqlContext.catalog.persistTable(crossdataTable, sqlContext.catalog.createLogicalRelation(crossdataTable))
      }
      val tableSeq = DDLUtils.tableIdentifierToSeq(tableId)
      Row(tableSeq, ignoreTable)
    }

  }
}

private[crossdata] case class DropTable(tableIdentifier: TableIdentifier) extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    sqlContext.catalog.dropTable(tableIdentifier)
    Seq.empty
  }

}

private[crossdata] case object DropAllTables extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    sqlContext.catalog.dropAllTables()
    Seq.empty
  }

}

private[crossdata] case class InsertIntoTable(tableIdentifier: TableIdentifier, values: Seq[String])
  extends RunnableCommand {

  override def output: Seq[Attribute] = {
    val schema = StructType(
      Seq(StructField("Rows", IntegerType, nullable = false))
    )
    schema.toAttributes
  }

  override def run(sqlContext: SQLContext): Seq[Row] = {

    sqlContext.catalog.lookupRelation(tableIdentifier) match {
      case LogicalRelation( insertableRelation: InsertableRelation, _) =>
        val tableSchema = insertableRelation.schema
        if(tableSchema.fields.length != values.length) sys.error("Invalid length of parameters")

        val valuesConverted = tableSchema.fields zip values map {
          case(schemaCol, value) =>
            DDLUtils.convertSparkDatatypeToScala(value, schemaCol.dataType) map {
              case Success(converted) => converted
              case Failure(exception) => throw exception
            }
        }

        val dataframe = sqlContext.asInstanceOf[XDContext].createDataFrame(Seq(Row(valuesConverted)),tableSchema)
        insertableRelation.insert(dataframe, overwrite = false)

      case _ =>
        sys.error("The Datasource does not support INSERT INTO table VALUES command")
    }

    Row(1) :: Nil
  }
}

private[crossdata] case class CreateTempView(viewIdentifier: ViewIdentifier, queryPlan: LogicalPlan)
  extends RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    sqlContext.catalog.registerView(viewIdentifier, queryPlan)
    Seq.empty
  }

}

private[crossdata] case class CreateView(viewIdentifier: ViewIdentifier, queryPlan: LogicalPlan, sql: String)
  extends LogicalPlan with RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    sqlContext.catalog.persistView(viewIdentifier, queryPlan, sql)
    Seq.empty
  }

}


private[crossdata] case class DropView(viewIdentifier: ViewIdentifier)
  extends LogicalPlan with RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    sqlContext.catalog.dropView(viewIdentifier)
    Seq.empty
  }
}

private[crossdata] case class AddJar(jarPath: String)
  extends LogicalPlan with RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    if (jarPath.toLowerCase.startsWith("hdfs://") || File(jarPath).exists) {
      sqlContext.sparkContext.addJar(jarPath)
      Seq.empty
    } else {
      sys.error("File doesn't exists or is not a hdfs file")
    }
  }
}

case class CreateExternalTable(
                                tableIdent: TableIdentifier,
                                userSpecifiedSchema: StructType,
                                provider: String,
                                options: Map[String, String],
                                allowExisting: Boolean = false) extends LogicalPlan with RunnableCommand {


  override def run(sqlContext: SQLContext): Seq[Row] = {

    val resolved = ResolvedDataSource.lookupDataSource(provider).newInstance()

    resolved match {

      case _ if sqlContext.catalog.tableExists(tableIdent) =>
        throw new AnalysisException(s"Table ${tableIdent.unquotedString} already exists")

      case tableManipulation: TableManipulation =>

        val tableInventory = tableManipulation.createExternalTable(sqlContext, tableIdent.table, tableIdent.database, userSpecifiedSchema, options)
        tableInventory.map{ tableInventory =>
          val optionsWithTable = tableManipulation.generateConnectorOpts(tableInventory, options)
          val crossdataTable = CrossdataTable(tableIdent.table, tableIdent.database, Option(userSpecifiedSchema), provider, Array.empty, optionsWithTable)
          sqlContext.catalog.persistTable(crossdataTable, sqlContext.catalog.createLogicalRelation(crossdataTable))
        } getOrElse( throw new RuntimeException(s"External table can't be created"))

      case _ =>
        sys.error("The Datasource does not support CREATE EXTERNAL TABLE command")
    }

    Seq.empty

  }

}

